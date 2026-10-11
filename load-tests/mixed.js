// Realistic mixed traffic: mostly browsing, some pricing, a few bookings and retries.
//   VUS=40 DURATION=2m docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/mixed.js
import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import {
  BULK_ROOM, createQuote, createReservation, dateIn, DEMO_HOTELS, pay, PRICING_URL, record, registerUsers, RESERVATION_URL, uniqueKey,
} from './lib/common.js'

const VUS = Number(__ENV.VUS || 40)

export const options = {
  setupTimeout: '2m',
  scenarios: {
    shoppers: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s', target: VUS },
        { duration: __ENV.DURATION || '2m', target: VUS },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{name:GET /api/hotels}': ['p(95)<400'],
    farelock_failed_requests: ['count<10'],
  },
}

export function setup() {
  return { users: registerUsers(VUS, `mixed-${Date.now()}`) }
}

const pick = (list) => list[Math.floor(Math.random() * list.length)]
const randomDate = () => dateIn(1 + Math.floor(Math.random() * 30))

// Traffic mix: 45% search, 25% hotel detail, 15% direct price check, 10% booking, 5% booking with a retry.
export default function (data) {
  const roll = Math.random()
  const cookie = data.users[(exec.vu.idInTest - 1) % data.users.length]

  if (roll < 0.45) {
    const res = http.get(`${RESERVATION_URL}/api/hotels?date=${randomDate()}`, { tags: { name: 'GET /api/hotels' } })
    record(res, 'search')
  } else if (roll < 0.7) {
    const res = http.get(`${RESERVATION_URL}/api/hotels/${pick(DEMO_HOTELS)}?date=${randomDate()}`, { tags: { name: 'GET /api/hotels/{id}' } })
    record(res, 'hotel')
  } else if (roll < 0.85) {
    const res = http.get(`${PRICING_URL}/api/prices?hotelId=${pick(DEMO_HOTELS)}&date=${randomDate()}`, { tags: { name: 'GET /api/prices' } })
    record(res, 'prices')
  } else {
    const stayDate = dateIn(70)
    const quoteId = createQuote(BULK_ROOM, stayDate)
    if (quoteId) {
      const key = uniqueKey('mixed')
      let res = createReservation(cookie, quoteId, BULK_ROOM, stayDate, key)
      if (roll >= 0.95) {
        // The client "lost" the response and retries with the same key: must get the same booking.
        const retry = createReservation(cookie, quoteId, BULK_ROOM, stayDate, key)
        check(retry, { 'retry replays the booking': (r) => r.status === 201 && r.json('id') === res.json('id') })
        res = retry
      }
      if (record(res, 'reserve', [201])) record(pay(cookie, res.json('id')), 'pay', [200])
    }
  }
  sleep(0.5 + Math.random()) // think time between page views
}
