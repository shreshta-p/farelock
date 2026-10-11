// Shared helpers for the FareLock k6 scenarios.
import http from 'k6/http'
import { check, fail } from 'k6'
import { Counter } from 'k6/metrics'

export const RESERVATION_URL = __ENV.RESERVATION_URL || 'http://localhost:8082'
export const PRICING_URL = __ENV.PRICING_URL || 'http://localhost:8081'

// Load tests create bookings and users. Refuse to run against anything but a local stack.
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1|host\.docker\.internal)(:\d+)?$/
if (!__ENV.ALLOW_NON_LOCAL && (!LOCAL.test(RESERVATION_URL) || !LOCAL.test(PRICING_URL))) {
  throw new Error(`Refusing to load-test a non-local target (${RESERVATION_URL}). Set ALLOW_NON_LOCAL=1 only for a disposable test environment.`)
}

export const LOAD_TEST_HOTEL = 99
export const CONTENDED_ROOM = 9901 // 10 rooms per night
export const BULK_ROOM = 9902 // 100,000 rooms per night
export const DEMO_HOTELS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]

// Successful and failed requests are counted separately, so throughput numbers only include real successes.
export const succeeded = new Counter('farelock_succeeded_requests')
export const failed = new Counter('farelock_failed_requests')

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** ISO date `days` from today (UTC), inside the 90-day booking window. */
export function dateIn(days) {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function uniqueKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`
}

/** Records a response as success or failure. `ok` lists the statuses that count as success for this call. */
export function record(res, name, ok = [200, 201]) {
  const good = ok.includes(res.status)
  ;(good ? succeeded : failed).add(1, { name })
  if (res.status === 0) console.warn(`${name}: network error ${res.error_code} ${res.error}`) // never reached the server
  return good
}

/** Registers `count` throwaway users in parallel batches and returns their session cookies. */
export function registerUsers(count, runId) {
  const cookies = []
  for (let start = 0; start < count; start += 20) {
    const batch = []
    for (let i = start; i < Math.min(count, start + 20); i++) {
      batch.push([
        'POST',
        `${RESERVATION_URL}/api/auth/register`,
        JSON.stringify({ email: `loadtest+${runId}-${i}@farelock.test`, password: 'load-test-password', fullName: `Load Test ${i}` }),
        { headers: JSON_HEADERS },
      ])
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 201) fail(`could not register a load-test user: ${res.status} ${res.body}`)
      cookies.push(`farelock_session=${res.cookies.farelock_session[0].value}`)
    }
  }
  return cookies
}

export const authed = (cookie, extra = {}) => ({ headers: { ...JSON_HEADERS, Cookie: cookie, ...extra } })

export function createQuote(roomTypeId, stayDate) {
  const res = http.post(`${PRICING_URL}/api/quotes`, JSON.stringify({ roomTypeId, stayDate }), {
    headers: JSON_HEADERS,
    tags: { name: 'POST /api/quotes' },
  })
  record(res, 'quote', [201])
  return res.status === 201 ? res.json('id') : null
}

export function createReservation(cookie, quoteId, roomTypeId, stayDate, key) {
  const body = JSON.stringify({ quoteId, roomTypeId, stayDate, guestName: 'Load Test Guest' })
  return http.post(`${RESERVATION_URL}/api/reservations`, body, {
    ...authed(cookie, { 'Idempotency-Key': key }),
    tags: { name: 'POST /api/reservations' },
  })
}

export function pay(cookie, reservationId, token = 'tok_approve') {
  return http.post(`${RESERVATION_URL}/api/reservations/${reservationId}/payment`, JSON.stringify({ paymentToken: token }), {
    ...authed(cookie),
    tags: { name: 'POST /api/reservations/{id}/payment' },
  })
}

/** Free rooms of a load-test room type on a date, read through the public API. */
export function availability(roomTypeId, stayDate) {
  const res = http.get(`${RESERVATION_URL}/api/hotels/${LOAD_TEST_HOTEL}?date=${stayDate}`, { tags: { name: 'GET /api/hotels/{id}' } })
  check(res, { 'hotel detail loaded': (r) => r.status === 200 })
  return res.json('rooms').find((r) => r.room.id === roomTypeId).available
}
