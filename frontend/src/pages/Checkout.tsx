import { Clock, Lock } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { api, ApiError, type PaymentToken, type Reservation } from '../api'
import { useAuth } from '../auth'
import { Photo } from '../components/Photo'
import { Button, Field, Notice, Page, Skeleton, StateMessage, TextLink } from '../components/ui'
import { formatDate, formatMoney } from '../lib/format'
import { useLoad } from '../lib/useLoad'

const PAYMENT_OPTIONS: { token: PaymentToken; label: string; detail: string }[] = [
  { token: 'tok_approve', label: 'Approved', detail: 'The payment goes through.' },
  { token: 'tok_decline', label: 'Declined', detail: 'The bank says no. Your room is released.' },
  { token: 'tok_timeout', label: 'Provider timeout', detail: 'No answer from the provider. We check later before releasing the room.' },
  { token: 'tok_lost_response', label: 'Lost response', detail: "The payment succeeds but its answer is lost. Reconciliation confirms it." },
  { token: 'tok_unknown', label: 'Pending at the bank', detail: 'The outcome is unknown for about 20 seconds, then approved.' },
]

/** One idempotency key per quote, kept across refreshes, so resubmitting can never book twice. */
function idempotencyKeyFor(quoteId: string) {
  const storageKey = `farelock:checkout:${quoteId}`
  let key = sessionStorage.getItem(storageKey)
  if (!key) {
    key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
  }
  return key
}

function useCountdown(expiresAt: string | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])
  if (!expiresAt) return null
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 1000))
}

export function Checkout() {
  const { quoteId = '' } = useParams()
  const { user } = useAuth()
  const navigate = useNavigate()

  const load = useLoad(async () => {
    const quote = await api.quote(quoteId)
    const hotel = await api.hotel(quote.hotelId, quote.stayDate)
    return { quote, hotel }
  }, [quoteId])

  const [guestName, setGuestName] = useState(user?.fullName ?? '')
  const [token, setToken] = useState<PaymentToken>('tok_approve')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reservation, setReservation] = useState<Reservation | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const secondsLeft = useCountdown(load.data?.quote.expiresAt)

  if (load.error) {
    return (
      <Page>
        <StateMessage title="This checkout link is no longer valid" action={<TextLink to="/search">Find a hotel</TextLink>}>
          {load.error.message}
        </StateMessage>
      </Page>
    )
  }

  const data = load.data
  const room = data?.hotel.rooms.find((r) => r.room.id === data.quote.roomTypeId)?.room
  const expired = secondsLeft === 0 && !reservation

  const refreshQuote = async () => {
    if (!data) return
    setRefreshing(true)
    try {
      const fresh = await api.createQuote(data.quote.roomTypeId, data.quote.stayDate)
      navigate(`/checkout/${fresh.id}`, { replace: true })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not refresh the price')
    } finally {
      setRefreshing(false)
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!data) return
    if (!guestName.trim()) {
      setError('Please enter the name of the guest staying.')
      return
    }
    setSubmitting(true)
    setError(null)
    let held = reservation
    try {
      // Step 1: hold the room. Safe to repeat thanks to the idempotency key.
      held ??= await api.createReservation(
        { quoteId, roomTypeId: data.quote.roomTypeId, stayDate: data.quote.stayDate, guestName: guestName.trim() },
        idempotencyKeyFor(quoteId),
      )
      setReservation(held)
      // Step 2: pay. 200 = confirmed, 202 = still verifying; the confirmation page shows both.
      await api.pay(held.id, token)
      navigate(`/reservations/${held.id}`, { replace: true })
    } catch (err) {
      // A decline is a final outcome recorded on the reservation: show it on its page.
      if (held && err instanceof ApiError && err.code === 'PAYMENT_DECLINED') {
        navigate(`/reservations/${held.id}`, { replace: true })
        return
      }
      setError(err instanceof ApiError ? err.message : 'Something went wrong. You can safely try again.')
      setSubmitting(false)
    }
  }

  return (
    <Page className="pt-12">
      <p className="eyebrow">Checkout</p>
      <h1 className="display mt-4 text-5xl">Confirm your stay</h1>

      <div className="mt-12 grid gap-14 lg:grid-cols-12">
        <form onSubmit={submit} className="space-y-12 lg:col-span-7" noValidate>
          <fieldset>
            <legend className="display mb-6 text-2xl">
              <span className="mr-3 text-sm font-semibold text-clay">01</span>Guest
            </legend>
            <Field
              label="Full name of the guest"
              name="guestName"
              autoComplete="name"
              value={guestName}
              onChange={(e) => setGuestName(e.target.value)}
              maxLength={100}
              required
            />
          </fieldset>

          <fieldset>
            <legend className="display mb-2 text-2xl">
              <span className="mr-3 text-sm font-semibold text-clay">02</span>Payment
            </legend>
            <p className="mb-6 text-sm text-ink-soft">
              Payments are simulated. Choose how the test provider should respond. No card details are needed or collected.
            </p>
            <div className="divide-y divide-line border-y border-line">
              {PAYMENT_OPTIONS.map((o) => (
                <label key={o.token} className="flex cursor-pointer items-start gap-4 py-4 transition-colors hover:bg-card/70">
                  <input
                    type="radio"
                    name="payment"
                    value={o.token}
                    checked={token === o.token}
                    onChange={() => setToken(o.token)}
                    className="mt-1.5 h-4 w-4 accent-[var(--color-ink)]"
                  />
                  <span>
                    <span className="block font-medium">{o.label}</span>
                    <span className="block text-sm text-muted">{o.detail}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {error && <Notice>{error}</Notice>}

          <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
            <Button type="submit" busy={submitting} disabled={!data || expired} className="sm:w-64">
              <Lock className="h-4 w-4" aria-hidden />
              {data ? `Pay ${formatMoney(data.quote.amount, data.quote.currency)}` : 'Pay'}
            </Button>
            <p className="text-xs text-muted">Your room is held for 10 minutes once you press pay.</p>
          </div>
        </form>

        <aside className="lg:col-span-5">
          <div className="border border-line bg-card lg:sticky lg:top-24">
            {!data ? (
              <div className="p-6">
                <Skeleton className="aspect-[3/2]" />
                <Skeleton className="mt-5 h-6 w-2/3" />
              </div>
            ) : (
              <>
                {room?.image && <Photo photo={room.image} className="aspect-[3/2]" sizes="(min-width: 1024px) 35vw, 100vw" />}
                <div className="p-6">
                  <p className="eyebrow">{data.hotel.hotel.city}</p>
                  <p className="display mt-2 text-2xl">{data.hotel.hotel.name}</p>
                  <dl className="mt-5 space-y-3 border-t border-line pt-5 text-sm">
                    <div className="flex justify-between gap-4"><dt className="text-muted">Room</dt><dd className="text-right">{room?.name}</dd></div>
                    <div className="flex justify-between gap-4"><dt className="text-muted">Night</dt><dd className="text-right">{formatDate(data.quote.stayDate)}</dd></div>
                    <div className="flex justify-between gap-4"><dt className="text-muted">Guests</dt><dd>Up to {room?.maxGuests}</dd></div>
                  </dl>
                  <div className="mt-5 flex items-baseline justify-between border-t border-ink pt-5">
                    <span className="font-medium">Total</span>
                    <span className="display text-3xl">{formatMoney(data.quote.amount, data.quote.currency)}</span>
                  </div>
                  <div className="mt-5" aria-live="polite">
                    {expired ? (
                      <Notice tone="info" title="This price has expired">
                        <button type="button" onClick={refreshQuote} disabled={refreshing} className="link mt-1 font-medium">
                          {refreshing ? 'Getting a fresh price…' : 'Get a fresh price'}
                        </button>
                      </Notice>
                    ) : (
                      secondsLeft !== null && (
                        <p className="flex items-center gap-2 text-sm text-ink-soft">
                          <Clock className="h-4 w-4 text-clay" aria-hidden />
                          Price locked for{' '}
                          <span className="font-semibold tabular-nums text-ink">
                            {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')}
                          </span>
                        </p>
                      )
                    )}
                  </div>
                </div>
              </>
            )}
          </div>
        </aside>
      </div>
    </Page>
  )
}
