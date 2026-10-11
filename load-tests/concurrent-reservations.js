// Many customers book and pay at the same time; every booking must be counted exactly once.
//   VUS=50 DURATION=45s docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/concurrent-reservations.js
import { check } from 'k6'
import exec from 'k6/execution'
import { Counter } from 'k6/metrics'
import { availability, BULK_ROOM, createQuote, createReservation, dateIn, pay, record, registerUsers, uniqueKey } from './lib/common.js'

const VUS = Number(__ENV.VUS || 50)
const confirmed = new Counter('bookings_confirmed')

export const options = {
  setupTimeout: '2m',
  scenarios: {
    bookings: {
      executor: 'ramping-vus',
      stages: [
        { duration: '15s', target: VUS }, // ramp up
        { duration: __ENV.DURATION || '45s', target: VUS }, // sustained load
        { duration: '10s', target: 0 }, // ramp down
      ],
    },
  },
  thresholds: { checks: ['rate==1.0'] },
}

export function setup() {
  const stayDate = __ENV.STAY_DATE || dateIn(60)
  return { stayDate, before: availability(BULK_ROOM, stayDate), users: registerUsers(VUS, `concurrent-${Date.now()}`) }
}

export default function (data) {
  const cookie = data.users[(exec.vu.idInTest - 1) % data.users.length]
  const quoteId = createQuote(BULK_ROOM, data.stayDate)
  if (!quoteId) return
  const reserved = createReservation(cookie, quoteId, BULK_ROOM, data.stayDate, uniqueKey('concurrent'))
  record(reserved, 'reserve', [201])
  if (!check(reserved, { 'reserved (201)': (r) => r.status === 201 })) return
  const paid = pay(cookie, reserved.json('id'))
  record(paid, 'pay', [200])
  if (check(paid, { 'paid and confirmed': (r) => r.status === 200 && r.json('status') === 'CONFIRMED' })) confirmed.add(1)
}

export function teardown(data) {
  const taken = data.before - availability(BULK_ROOM, data.stayDate)
  // Must equal the bookings_confirmed counter in the summary: no lost or double-counted rooms.
  console.log(`Rooms taken on ${data.stayDate}: ${taken}. Compare with bookings_confirmed.`)
}
