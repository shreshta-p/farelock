// Pricing throughput and latency at a fixed request rate. Run twice and compare:
//   cache on : the pricing service as normal
//   cache off: restart pricing with FARELOCK_CACHE_ENABLED=false
//   RATE=300 DURATION=1m docker compose --env-file .env --profile loadtest run --rm k6 run /scripts/pricing-load.js
import http from 'k6/http'
import { check } from 'k6'
import { dateIn, DEMO_HOTELS, PRICING_URL, record } from './lib/common.js'

export const options = {
  scenarios: {
    prices: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 300),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
}

// 10 hotels x 14 nights = 140 distinct cache keys, a realistic amount of repetition for browsing.
export default function () {
  const hotel = DEMO_HOTELS[Math.floor(Math.random() * DEMO_HOTELS.length)]
  const date = dateIn(10 + Math.floor(Math.random() * 14))
  const res = http.get(`${PRICING_URL}/api/prices?hotelId=${hotel}&date=${date}`, { tags: { name: 'GET /api/prices' } })
  record(res, 'prices', [200])
  check(res, { 'prices returned': (r) => r.status === 200 })
}
