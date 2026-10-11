// 100 customers race for the same 10 rooms on the same night. Exactly 10 may win.
//   docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/inventory-contention.js
import { check } from 'k6'
import exec from 'k6/execution'
import { Counter } from 'k6/metrics'
import { availability, CONTENDED_ROOM, createQuote, createReservation, dateIn, record, registerUsers, uniqueKey } from './lib/common.js'

const CUSTOMERS = Number(__ENV.CUSTOMERS || 100)
const ROOMS = 10
const created = new Counter('reservations_created')
const soldOut = new Counter('reservations_sold_out')

export const options = {
  setupTimeout: '2m',
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: CUSTOMERS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    reservations_created: [`count<=${ROOMS}`], // never oversell
    checks: ['rate==1.0'],
  },
}

export function setup() {
  // A night only this test touches; reset.sql clears it between runs.
  const stayDate = __ENV.STAY_DATE || dateIn(45)
  const before = availability(CONTENDED_ROOM, stayDate)
  if (before !== ROOMS) throw new Error(`Expected ${ROOMS} free rooms on ${stayDate}, found ${before}. Run load-tests/reset.sql first.`)
  // Users and quotes are prepared up front so all reservation requests hit the server together.
  const users = registerUsers(CUSTOMERS, `contention-${Date.now()}`)
  const quotes = users.map(() => createQuote(CONTENDED_ROOM, stayDate))
  return { stayDate, users, quotes }
}

export default function (data) {
  const i = exec.vu.idInTest - 1
  const res = createReservation(data.users[i], data.quotes[i], CONTENDED_ROOM, data.stayDate, uniqueKey('contention'))
  record(res, 'reserve', [201, 409])
  if (res.status === 201) created.add(1)
  if (res.status === 409 && res.json('code') === 'SOLD_OUT') soldOut.add(1)
  check(res, { 'either reserved or sold out': (r) => r.status === 201 || (r.status === 409 && r.json('code') === 'SOLD_OUT') })
}

export function teardown(data) {
  const left = availability(CONTENDED_ROOM, data.stayDate)
  check(left, { 'all rooms taken, none oversold': (n) => n === 0 })
  console.log(`Rooms left on ${data.stayDate}: ${left}. Expect reservations_created=10 and reservations_sold_out=${CUSTOMERS - ROOMS}.`)
}
