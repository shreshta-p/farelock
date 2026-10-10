# Architecture decisions

Short notes on choices that a reviewer might question. Newest at the bottom.

### 1. Two services, one Postgres, service-owned schemas
Pricing and Reservation are separate Spring Boot apps that talk over REST. They share one
Postgres *instance*, but each logs in with its own role and owns only its own schema
(`pricing`, `reservation`, `payment_provider`). Neither service can read the other's tables.
**Tradeoff:** one instance is a single point of failure, but separate instances would add
nothing to a local demo.

### 2. Spring JDBC + plain SQL, no JPA
Inventory correctness depends on exact SQL (conditional `UPDATE ... WHERE held + booked < total`).
Writing that SQL directly is easier to read and explain than JPA locking annotations.

### 3. Configuration through a repo-root `.env`
Docker Compose reads `.env`, and each service imports the same file with
`spring.config.import: optional:file:../.env[.properties]`, so there is one place for
local secrets. Real deployments would use environment variables instead; the file is optional.

### 4. Prices: two simple multipliers, BigDecimal, explicit currency
`base × 1.15 (Fri/Sat) × 1.10 (within 7 days)`, rounded HALF_EVEN to cents. Every amount
travels with its ISO currency code. A **quote** stores the computed amount, so later rate
changes never alter a price the customer already saw. Quotes expire after 15 minutes.

### 5. Redis is an optimisation, never a dependency
Prices are cached for 60s under `prices:v1:{hotelId}:{date}`. Any Redis error becomes a cache
miss. Lettuce rejects commands immediately while disconnected, and after a failure the cache is
skipped for 5s, so an outage does not add a timeout to every request.
**Tradeoff:** a price cached just before midnight may keep yesterday's "last-minute" multiplier
for up to 60s. That is acceptable because the *quote* is what a booking is charged against.

### 6. Sessions: opaque token in an HttpOnly cookie, hash in Postgres
On login a random 256-bit token goes into a `HttpOnly; SameSite=Lax` cookie, and only its SHA-256
hash is stored. Sessions survive restarts and can be revoked by deleting a row. No JWTs, because there
is nothing to gain from stateless tokens here and revocation would get harder. BCrypt comes from
`spring-security-crypto` alone. The full Spring Security filter chain would be more to explain than
the ~30 lines of interceptor it replaces. **CSRF:** SameSite=Lax stops browsers sending the cookie on
cross-site POSTs, and every state-changing endpoint requires a JSON body.

### 7. Inventory: one row per room type per night, changed only by conditional UPDATEs
`UPDATE room_inventory SET held = held + 1 WHERE ... AND held + booked < total` is atomic.
Postgres row-locks during the statement, so 100 concurrent requests for 10 rooms produce exactly
10 successes (`ConcurrencyTest`). A `CHECK (held + booked <= total)` constraint backs it up.
**Why not `SELECT ... FOR UPDATE` then UPDATE?** Two statements and a held lock to do what one
statement does. **Why not optimistic versioning?** Retries under contention, for no benefit here.
**Tradeoff:** every booking for the same room type and night serialises on one row. That is fine
at hotel-booking volumes. A very hot row could be split into buckets later.

### 8. Idempotency keys in Postgres, response stored in the business transaction
`(user_id, idempotency_key)` is the primary key, so concurrent duplicates cannot both claim it.
The reservation insert and the "COMPLETED + response body" update commit in **one** transaction,
so there is never a booking without its stored response, or the reverse. Definite 4xx answers
(sold out, quote expired) are stored and replayed. Temporary failures (pricing down, 5xx) delete
the key so the client can retry. A key stuck `IN_PROGRESS` for over 60s (process crashed) can be
taken over. All remote calls have timeouts well under that. Keys are scoped per user.

### 9. Coroutines only where they buy something
Spring MVC + JDBC stay blocking. Coroutines are used for (a) concurrent fan-out: search fetches
every hotel's prices at once, and hotel detail loads the catalog and prices in parallel; (b) the
Pricing call in create-reservation; and later (c) bounding the payment call with `withTimeout`.
Rule: a transaction lives entirely inside one `io { }` block and never crosses a suspension point.

### 10. Payments: explicit states, provider as the source of truth, reconciliation instead of retries
States: `HELD → PAYMENT_PENDING → CONFIRMED`, or `→ RELEASED` (EXPIRED / DECLINED / PAYMENT_NOT_FOUND).
The reservation moves to `PAYMENT_PENDING` **before** calling the provider, so hold expiry can never
give away a room that might be paid for. The provider call has no transaction open and is bounded with
`withTimeout` + `runInterruptible`. A timeout means "unknown", not "failed", and the API answers
`202 PAYMENT_VERIFYING`. The reconciler later asks the provider (`lookup(reservationId)`) and applies the
real outcome. Inventory is released only after a confirmed decline, or once the provider still has no
record of the charge after a 2-minute grace period. The reservation id is the provider's idempotency
key, so a reservation can be charged at most once, no matter how often pay is retried.
**Pay has no Idempotency-Key:** the state machine plus the provider key already make it safe to retry.
**No locks in jobs:** the reconciler must not hold a transaction while it asks the provider, so instead of
`FOR UPDATE SKIP LOCKED` every change is a conditional transition (`WHERE status = 'PAYMENT_PENDING'`)
with its inventory update in the same transaction. A duplicate run simply finds nothing to change.
**Simulator limit:** a real provider can complete a charge after we gave up and released the room. The fix
would be a void/refund call. The simulator never records charges after the caller's deadline.
