# Benchmarks

All numbers below were measured on a developer laptop, on 2026-10-10, with the commands shown.
They show relative behaviour (before vs after, cache on vs off) under identical local conditions.
They are **not** production capacity claims. Each configuration was run once, so small
differences (±5%) are noise.

## Setup

| | |
|---|---|
| Machine | Windows 11, 32 logical CPUs |
| Services | `./gradlew bootRun` on the host, JDK 21 (Temurin 21.0.12), default JVM flags |
| Database / cache | PostgreSQL 17 and Redis 7.4 in Docker Desktop (16 GB VM) |
| Load generator | k6 1.2.3 in Docker, reaching the services through `host.docker.internal` |
| Data | `load-tests/reset.sql` before each booking run; hidden load-test hotel 99 |

**Harness caveat.** Roughly 1 in 2,000 k6 requests failed with `dial: i/o timeout`. The k6 container
could not open a TCP connection through Docker Desktop's host networking, so the request never reached
either service: server-side request counts and logs show no matching errors. These requests are still
reported as failures (`farelock_failed_requests`) and are not hidden.

## Correctness under load

| Scenario | Command | Result |
|---|---|---|
| Inventory contention | `inventory-contention.js` (100 users, 1 attempt each, 10 rooms) | **10 created, 90 `SOLD_OUT`, 0 rooms left**: no overselling |
| Idempotency stress | `idempotency-stress.js` (20 VUs × 5 bookings, each sent 10× in parallel with one key, then paid twice in parallel) | **100 reservations, 100 charges, 0 duplicates**. Every late retry replayed the original booking |
| Concurrent bookings | `concurrent-reservations.js` | In every run, rooms taken (from the API) == `bookings_confirmed` (e.g. 20,407 == 20,407) |
| Failure recovery | `failure-recovery.js` for 60s, with `docker compose stop redis` for 20s mid-run | Prices kept answering (53 cache errors, all served from Postgres). 48 payments ended "unknown" (timeout or lost response): reconciliation **confirmed 24, released 24, left 0 unresolved** |
| Mixed traffic | `mixed.js`, 40 VUs, 2m sustained | 0 failed requests. `GET /api/hotels` p95 10.5 ms, all requests p95 15.9 ms |

## Performance investigation: booking throughput

### 1. Baseline

`VUS=100 DURATION=60s concurrent-reservations.js`. Each iteration: quote → reserve → pay, all for the same
room type and night (the hidden "bulk" room with 100,000 rooms). Grafana and `/actuator/prometheus` showed:

| Metric (steady state) | Value |
|---|---|
| Confirmed bookings/s | **149.8** |
| p95 all requests (k6) | 491 ms |
| p95 `POST /api/reservations` (server) | 342 ms |
| p95 `POST /api/reservations/{id}/payment` (server) | 576 ms |
| HikariCP active / pending (reservation service) | 10 of 10 / **68 waiting** |
| Avg wait for a DB connection | 45 ms |
| Reservation service CPU | ~4% |
| Pricing p95 (`/api/quotes`) | 5 ms. Not the bottleneck |

The pool was saturated while CPU sat idle, so connections were being held while *waiting* on something.

### 2. First hypothesis (wrong): pool too small

Restarted with `DB_POOL_SIZE=30` and kept everything else identical:

| | Pool 10 | Pool 30 |
|---|---|---|
| Confirmed bookings/s | 149.8 | 145.7 |
| p95 reserve / pay | 342 / 576 ms | 448 / 594 ms |
| Pending connections | 68 | 43 (all 30 busy) |

No improvement. More connections just meant more transactions waiting at the same place.

### 3. Finding the cause

Sampling `pg_stat_activity` during the run (`wait_event_type`, `wait_event`, `query`) showed most busy
sessions in `Lock: transactionid` / `Lock: tuple` on `UPDATE room_inventory`, which is **row-lock contention**
on the single inventory row every booking for that night updates. It also showed sessions in
`Client: ClientRead` running `INSERT INTO reservations` or `SELECT r.* …` **while holding that lock**.
The create-reservation transaction took the inventory lock first and then did three more round-trips
(insert, re-read, idempotency update) before committing. Everyone else queued behind those round-trips.

### 4. Fix: take the hot lock last

`ReservationService.holdRoom` now inserts the reservation and records the idempotent response first, and
runs the conditional inventory `UPDATE` as the **last statement before commit**. Correctness is
unchanged: it is still one transaction, and `SOLD_OUT` rolls back the insert. The inventory row is now
locked for one statement plus the commit. The payment path already updated inventory last.

### 5. After (pool back to 10, same command)

| Metric | Before | After | Change |
|---|---|---|---|
| Confirmed bookings/s | 149.8 | **224.4** | **+50%** |
| p95 all requests (k6) | 491 ms | 305 ms | −38% |
| p95 `POST /api/reservations` | 342 ms | 203 ms | −41% |
| p95 `POST …/payment` | 576 ms | 360 ms | −38% |
| p95 iteration (quote + reserve + pay) | 814 ms | 517 ms | −36% |

The 100,000-room bulk row is a worst case on purpose: all 100 VUs fight over one row. Real traffic
spreads over many room types and nights, so contention is far lower. Going further would mean splitting
a hot row into buckets, which isn't justified at this scale (see `docs/decisions.md` #7).

## Pricing cache: an honest null result

`pricing-load.js` at a constant arrival rate, 45s each. Cache off = pricing restarted with `FARELOCK_CACHE_ENABLED=false`.

| | Rate | Achieved | p95 | Notes |
|---|---|---|---|---|
| Cache on | 300/s | 294/s | 3.24 ms | 98.9% hit ratio |
| Cache off | 300/s | 287/s | 3.19 ms | |
| Cache off | 1000/s | 958/s | 3.10 ms | |
| Cache on | 1000/s | 940/s | 3.16 ms | Started on a just-restarted (cold) JVM: 848 dropped iterations early in the run, so average and max are not comparable |

**Locally, Redis does not make prices faster.** The uncached path is an indexed read of three rows from a
Postgres on the same machine, which costs about as much as a Redis round-trip. What the cache does is
take ~99% of these reads off the database (with the cache on, the pricing pool never had more than 4 active
connections). That matters once the database is remote or busy. The latency numbers alone don't
justify it. This is reported as measured rather than tuned to look good.

## Reproduce

```bash
docker compose --env-file .env --profile monitoring up -d
# start both services (see README), then:
docker compose --env-file .env exec -T postgres psql -U postgres -d farelock -f - < load-tests/reset.sql
docker compose --env-file .env --profile loadtest run --rm -e VUS=100 -e DURATION=60s k6 run /scripts/concurrent-reservations.js
```

On Git Bash for Windows, prefix `docker compose … run` commands with `MSYS_NO_PATHCONV=1` so `/scripts/…` is not rewritten.
Watch the "Bookings", "Resources → DB connection pool" and "Traffic → p95 latency by endpoint" panels in Grafana
(http://localhost:3001).
