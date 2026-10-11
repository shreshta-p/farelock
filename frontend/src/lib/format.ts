/** Money in the hotel's own currency; Intl picks the right decimals (none for JPY). Whole amounts drop ".00". */
export function formatMoney(amount: number, currency: string) {
  const whole = Number.isInteger(amount)
  return new Intl.NumberFormat('en-US', { style: 'currency', currency, ...(whole && { minimumFractionDigits: 0 }) }).format(amount)
}

// Dates are calendar dates ("2026-10-24"), so format them in UTC to avoid timezone shifts.
export function formatDate(isoDate: string, style: 'long' | 'short' = 'long') {
  const options: Intl.DateTimeFormatOptions =
    style === 'long'
      ? { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }
      : { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }
  return new Intl.DateTimeFormat('en-GB', options).format(new Date(`${isoDate}T00:00:00Z`))
}

export function isoDate(date: Date) {
  return date.toISOString().slice(0, 10)
}

export function addDays(iso: string, days: number) {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return isoDate(d)
}

/** Bookable window: today (UTC) through 90 days ahead, matching the backend. */
export const today = () => isoDate(new Date())
export const tomorrow = () => addDays(today(), 1)
export const lastBookableDate = () => addDays(today(), 90)

export function isBookableDate(date: string | null): date is string {
  return !!date && /^\d{4}-\d{2}-\d{2}$/.test(date) && date >= today() && date <= lastBookableDate()
}
