import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In development the browser only talks to Vite (one origin), so the session cookie
// just works. Vite forwards API calls to the right backend service.
const pricing = process.env.PRICING_URL ?? 'http://localhost:8081'
const reservation = process.env.RESERVATION_URL ?? 'http://localhost:8082'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api/prices': pricing,
      '/api/quotes': pricing,
      '/api': reservation,
    },
  },
})
