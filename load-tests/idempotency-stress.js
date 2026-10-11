// Each customer fires the same booking request 10 times at once (a frantic double-clicker),
// retries once more later, then pays twice at once. Expect one reservation and one charge each.
//   docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/idempotency-stress.js
import http from 'k6/http'
import { check } from 'k6'
import exec from 'k6/execution'
import { Counter } from 'k6/metrics'
import { authed, BULK_ROOM, createQuote, dateIn, record, registerUsers, RESERVATION_URL, uniqueKey } from './lib/common.js'

const VUS = Number(__ENV.VUS || 20)
const ITERATIONS = Number(__ENV.ITERATIONS || 5)
const duplicates = new Counter('duplicate_bookings')

export const options = {
  setupTimeout: '2m',
  scenarios: { stress: { executor: 'per-vu-iterations', vus: VUS, iterations: ITERATIONS, maxDuration: '3m' } },
  thresholds: { duplicate_bookings: ['count==0'], checks: ['rate==1.0'] },
}

export function setup() {
  return { stayDate: __ENV.STAY_DATE || dateIn(61), users: registerUsers(VUS, `idem-${Date.now()}`) }
}

export default function (data) {
  const cookie = data.users[exec.vu.idInTest - 1]
  const quoteId = createQuote(BULK_ROOM, data.stayDate)
  const key = uniqueKey('idem')
  const body = JSON.stringify({ quoteId, roomTypeId: BULK_ROOM, stayDate: data.stayDate, guestName: 'Double Clicker' })
  const params = { ...authed(cookie, { 'Idempotency-Key': key }), tags: { name: 'POST /api/reservations' } }

  const responses = http.batch(Array.from({ length: 10 }, () => ['POST', `${RESERVATION_URL}/api/reservations`, body, params]))
  responses.forEach((r) => record(r, 'reserve-retry', [201, 409]))
  const ids = new Set(responses.filter((r) => r.status === 201).map((r) => r.json('id')))
  check(responses, {
    'every answer is the booking or "in progress"': (rs) => rs.every((r) => r.status === 201 || r.json('code') === 'REQUEST_IN_PROGRESS'),
    'exactly one reservation id': () => ids.size === 1,
  })
  if (ids.size > 1) duplicates.add(ids.size - 1)
  if (ids.size !== 1) return

  // A retry after the burst must replay the same booking.
  const replay = http.post(`${RESERVATION_URL}/api/reservations`, body, params)
  check(replay, { 'late retry replays the same booking': (r) => r.status === 201 && ids.has(r.json('id')) })

  const id = [...ids][0]
  const payRequest = ['POST', `${RESERVATION_URL}/api/reservations/${id}/payment`, JSON.stringify({ paymentToken: 'tok_approve' }), authed(cookie)]
  const pays = http.batch([payRequest, payRequest])
  check(pays, { 'paying twice at once is safe': (rs) => rs.every((r) => r.status === 200 || r.status === 202) })
}

export function teardown() {
  console.log('Verify exactly one charge per reservation:')
  console.log(
    '  docker compose --env-file .env exec -T postgres psql -U postgres -d farelock -c ' +
      '"SELECT count(*) AS reservations, count(c.idempotency_key) AS charges FROM reservation.reservations r ' +
      'LEFT JOIN payment_provider.charges c ON c.idempotency_key = r.id::text WHERE r.hotel_id = 99"',
  )
}
