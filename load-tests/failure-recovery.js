// Bookings with unreliable payments, while you break things on purpose.
// During the run, in another terminal:
//   docker compose --env-file .env stop redis     (watch cache errors rise, prices keep working)
//   docker compose --env-file .env start redis    (watch hits come back)
// Payments mix timeouts and lost responses; at the end the test waits for reconciliation and
// checks that no booking is left in limbo.
//   DURATION=2m docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/failure-recovery.js
import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { Counter } from 'k6/metrics'
import {
  authed, BULK_ROOM, createQuote, createReservation, dateIn, DEMO_HOTELS, pay, PRICING_URL, record, registerUsers, RESERVATION_URL, uniqueKey,
} from './lib/common.js'

const VUS = Number(__ENV.VUS || 20)
const paymentOutcome = new Counter('payment_first_response')

export const options = {
  setupTimeout: '2m',
  teardownTimeout: '3m',
  scenarios: {
    browse: {
      executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '2m', exec: 'browse',
    },
    book: {
      executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 20, maxVUs: 50, exec: 'book',
    },
  },
  thresholds: { checks: ['rate==1.0'] },
}

export function setup() {
  return { users: registerUsers(20, `failure-${Date.now()}`), stayDate: dateIn(75) }
}

export function browse() {
  const res = http.get(`${PRICING_URL}/api/prices?hotelId=${DEMO_HOTELS[__ITER % 10]}&date=${dateIn(5 + (__ITER % 20))}`, { tags: { name: 'GET /api/prices' } })
  record(res, 'prices')
  check(res, { 'prices still answer (Redis down or not)': (r) => r.status === 200 })
  sleep(0.2)
}

const TOKENS = ['tok_approve', 'tok_approve', 'tok_timeout', 'tok_lost_response', 'tok_decline']

export function book(data) {
  const cookie = data.users[exec.scenario.iterationInTest % data.users.length]
  const quoteId = createQuote(BULK_ROOM, data.stayDate)
  if (!quoteId) return
  const res = createReservation(cookie, quoteId, BULK_ROOM, data.stayDate, uniqueKey('failure'))
  if (!record(res, 'reserve', [201])) return
  const paid = pay(cookie, res.json('id'), TOKENS[exec.scenario.iterationInTest % TOKENS.length])
  paymentOutcome.add(1, { status: String(paid.status) }) // 200 confirmed, 202 verifying, 402 declined
  record(paid, 'pay', [200, 202, 402])
}

export function teardown(data) {
  // Reconciliation runs every 10s; timed-out payments are released after a 2-minute grace period.
  console.log('Waiting up to 3 minutes for reconciliation to settle every payment...')
  let pending = -1
  for (let i = 0; i < 36 && pending !== 0; i++) {
    sleep(5)
    pending = 0
    for (const cookie of data.users) {
      const list = http.get(`${RESERVATION_URL}/api/reservations`, authed(cookie)).json()
      pending += list.filter((r) => r.status === 'PAYMENT_PENDING' || r.status === 'HELD').length
    }
  }
  check(pending, { 'no booking left unresolved after reconciliation': (n) => n === 0 })
  console.log(`Unresolved bookings after waiting: ${pending}`)
}
