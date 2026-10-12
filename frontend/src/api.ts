// Thin, typed wrapper around the FareLock REST APIs. Every booking action goes to
// the real backend; nothing here fakes a result.

export type Money = { amount: number; currency: string }

export type Hotel = {
  id: number
  name: string
  city: string
  country: string
  address: string
  latitude: number | null
  longitude: number | null
  starRating: number | null
  description: string
  amenities: string[]
  images: string[]
  sourceUrl: string | null
  timezone: string
}

export type RoomType = {
  id: number
  hotelId: number
  name: string
  description: string
  bed: string
  sizeSqm: number
  maxGuests: number
  image: string | null
}

export type HotelSummary = { hotel: Hotel; roomsAvailable: number; fromPrice: Money | null }
export type RoomOffer = { room: RoomType; available: number; price: Money | null }
export type HotelDetail = { hotel: Hotel; stayDate: string; rooms: RoomOffer[]; pricesAvailable: boolean }
export type Destination = { city: string; country: string; hotelCount: number }

export type Quote = {
  id: string
  roomTypeId: number
  hotelId: number
  stayDate: string
  amount: number
  /** Price of optional free cancellation, locked with the room price. */
  protectionFee: number
  currency: string
  expiresAt: string
  expired: boolean
}

export type ReservationStatus = 'HELD' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'RELEASED' | 'CANCELLED'

export type Reservation = {
  id: string
  hotelId: number
  hotelName: string
  hotelTimezone: string
  roomTypeId: number
  roomName: string
  stayDate: string
  guestName: string
  /** Room price. `total` adds the protection fee when one was bought. */
  amount: number
  protectionFee: number | null
  total: number
  currency: string
  status: ReservationStatus
  releaseReason: 'EXPIRED' | 'DECLINED' | 'PAYMENT_NOT_FOUND' | null
  holdExpiresAt: string
  /** Cancellation closes when the stay date begins at the hotel. */
  cancelDeadline: string
  cancelledAt: string | null
  refundAmount: number | null
  refundStatus: 'NONE' | 'PENDING' | 'REFUNDED' | null
  createdAt: string
}

export type User = { id: number; email: string; fullName: string }

export type PaymentToken = 'tok_approve' | 'tok_decline' | 'tok_timeout' | 'tok_lost_response' | 'tok_unknown'

/** An error response from the API: { code, message } plus the HTTP status. */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, {
      ...init,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...init.headers },
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'We could not reach FareLock. Check your connection and try again.')
  }
  const text = await res.text()
  const body = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new ApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? 'Something went wrong')
  }
  return body as T
}

const post = <T>(path: string, body: unknown, headers: Record<string, string> = {}) =>
  request<T>(path, { method: 'POST', body: JSON.stringify(body), headers })

export const api = {
  destinations: () => request<Destination[]>('/api/destinations'),
  searchHotels: (destination: string, date: string) =>
    request<HotelSummary[]>(`/api/hotels?destination=${encodeURIComponent(destination)}&date=${date}`),
  hotel: (id: number | string, date: string) => request<HotelDetail>(`/api/hotels/${id}?date=${date}`),

  createQuote: (roomTypeId: number, stayDate: string) => post<Quote>('/api/quotes', { roomTypeId, stayDate }),
  quote: (id: string) => request<Quote>(`/api/quotes/${id}`),

  /** The signed-in user, or null (204) when there is no session. */
  me: () => request<User | null>('/api/me'),
  register: (fullName: string, email: string, password: string) =>
    post<User>('/api/auth/register', { fullName, email, password }),
  login: (email: string, password: string) => post<User>('/api/auth/login', { email, password }),
  logout: () => post<void>('/api/auth/logout', {}),

  /** The Idempotency-Key makes retries (double clicks, refreshes, flaky networks) safe. */
  createReservation: (
    body: { quoteId: string; roomTypeId: number; stayDate: string; guestName: string; cancellationProtection: boolean },
    idempotencyKey: string,
  ) => post<Reservation>('/api/reservations', body, { 'Idempotency-Key': idempotencyKey }),
  pay: (reservationId: string, paymentToken: PaymentToken) =>
    post<Reservation>(`/api/reservations/${reservationId}/payment`, { paymentToken }),
  /** Safe to repeat: the server decides from the reservation's state, and refunds are keyed. */
  cancel: (reservationId: string) => post<Reservation>(`/api/reservations/${reservationId}/cancel`, {}),
  reservations: () => request<Reservation[]>('/api/reservations'),
  reservation: (id: string) => request<Reservation>(`/api/reservations/${id}`),
}
