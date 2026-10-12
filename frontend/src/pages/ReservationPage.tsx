import { motion } from 'motion/react'
import { Check, Loader2, Undo2, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { api, ApiError, type Reservation } from '../api'
import { Photo } from '../components/Photo'
import { Button, Notice, Page, Skeleton, StateMessage, TextLink } from '../components/ui'
import { formatDate, formatInstantIn, formatMoney } from '../lib/format'
import { useLoad } from '../lib/useLoad'

const confirmationCode = (id: string) => `FL-${id.slice(0, 8).toUpperCase()}`

function headline(r: Reservation) {
  switch (r.status) {
    case 'CONFIRMED':
      return { icon: Check, tone: 'text-moss', title: "You're booked.", text: `We look forward to welcoming ${r.guestName.split(' ')[0]}.` }
    case 'PAYMENT_PENDING':
      return {
        icon: Loader2,
        tone: 'text-clay',
        title: 'Confirming your payment…',
        text: "We haven't heard back from the payment provider yet. Your room stays held while we check, and you won't be charged twice. This page updates by itself.",
      }
    case 'HELD':
      return { icon: Loader2, tone: 'text-clay', title: 'Awaiting payment', text: 'This room is held for you, but no payment was made yet.' }
    case 'RELEASED':
      return {
        icon: X,
        tone: 'text-rust',
        title: r.releaseReason === 'DECLINED' ? 'Payment declined' : 'This reservation was released',
        text:
          r.releaseReason === 'DECLINED'
            ? 'Your bank declined the payment, so the room has been released. You have not been charged.'
            : r.releaseReason === 'EXPIRED'
              ? 'The ten-minute hold ran out before payment, so the room went back on sale.'
              : 'The payment provider has no record of a payment, so the room went back on sale. You have not been charged.',
      }
    case 'CANCELLED': {
      const refund = r.refundAmount !== null ? formatMoney(r.refundAmount, r.currency) : ''
      return {
        icon: r.refundStatus === 'PENDING' ? Loader2 : Undo2,
        tone: 'text-ink-soft',
        title: 'Your stay is cancelled',
        text:
          r.refundStatus === 'REFUNDED'
            ? `${refund} has been refunded to your original payment method. The cancellation fee is not refunded.`
            : r.refundStatus === 'PENDING'
              ? `We're sending your refund of ${refund} to your original payment method. This page updates by itself.`
              : r.protectionFee === null
                ? 'The room has been released. This booking was non-refundable, so the room price is not refunded.'
                : 'The room has been released. No payment had been taken, so nothing is due.',
      }
    }
  }
}

/** The policy line plus a two-step cancel button (no browser confirm dialogs). */
function Cancellation({ r, onCancelled }: { r: Reservation; onCancelled: () => void }) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const deadline = `${formatInstantIn(r.cancelDeadline, r.hotelTimezone)} hotel time`
  const open = (r.status === 'CONFIRMED' || r.status === 'HELD') && new Date(r.cancelDeadline).getTime() > Date.now()
  const refundable = r.protectionFee !== null && r.status === 'CONFIRMED'

  if (r.status !== 'CONFIRMED' && r.status !== 'HELD') return null
  if (!open) return <p className="mt-10 text-sm text-muted">Cancellation closed at {deadline}.</p>

  const cancel = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.cancel(r.id)
      onCancelled()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not cancel. You can safely try again.')
    } finally {
      setBusy(false)
      setConfirming(false)
    }
  }

  return (
    <section aria-labelledby="cancel-heading" className="mt-12 border-t border-line pt-8">
      <h2 id="cancel-heading" className="eyebrow">Cancellation</h2>
      <p className="mt-3 max-w-xl text-sm leading-relaxed text-ink-soft">
        {r.protectionFee !== null
          ? `Free cancellation until ${deadline}. You get ${formatMoney(r.amount, r.currency)} back; the ${formatMoney(r.protectionFee, r.currency)} protection fee is kept.`
          : `Non-refundable. You can cancel until ${deadline} to free the room, but the room price is not refunded.`}
      </p>
      {error && <div className="mt-4"><Notice>{error}</Notice></div>}
      <div className="mt-5 flex flex-wrap items-center gap-4">
        {confirming ? (
          <>
            <Button variant="outline" busy={busy} onClick={cancel}>
              {refundable ? `Cancel and refund ${formatMoney(r.amount, r.currency)}` : 'Yes, cancel this stay'}
            </Button>
            <button type="button" className="link text-sm" onClick={() => setConfirming(false)} disabled={busy}>
              Keep my stay
            </button>
          </>
        ) : (
          <button type="button" className="link text-sm font-medium" onClick={() => setConfirming(true)}>
            Cancel this stay
          </button>
        )}
      </div>
    </section>
  )
}

export function ReservationPage() {
  const { id = '' } = useParams()
  const res = useLoad(() => api.reservation(id), [id])
  const pending = res.data?.status === 'PAYMENT_PENDING' || res.data?.refundStatus === 'PENDING'

  // While a payment or refund outcome is unknown, check again every few seconds. Reconciliation finishes it on the server.
  useEffect(() => {
    if (!pending) return
    const t = setInterval(res.reload, 3000)
    return () => clearInterval(t)
  }, [pending, res.reload])

  if (res.error) {
    return (
      <Page>
        <StateMessage title="Reservation not found" action={<TextLink to="/reservations">See your stays</TextLink>} />
      </Page>
    )
  }
  const r = res.data
  if (!r) {
    return (
      <Page className="pt-16">
        <Skeleton className="h-16 w-2/3" />
        <Skeleton className="mt-10 h-64" />
      </Page>
    )
  }

  const h = headline(r)
  const Icon = h.icon
  return (
    <Page className="pt-16">
      <div className="grid gap-14 lg:grid-cols-12">
        <div className="lg:col-span-7" aria-live="polite">
          <motion.span
            initial={{ scale: 0.6, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ type: 'spring', stiffness: 260, damping: 18 }}
            className={`inline-flex h-12 w-12 items-center justify-center rounded-full border border-current ${h.tone}`}
          >
            <Icon className={`h-5 w-5 ${Icon === Loader2 ? 'animate-spin [animation-duration:2s]' : ''}`} aria-hidden />
          </motion.span>
          <h1 className="display mt-8 text-5xl sm:text-6xl">{h.title}</h1>
          <p className="mt-5 max-w-xl text-lg leading-relaxed text-ink-soft">{h.text}</p>

          <dl className="mt-12 grid grid-cols-2 gap-y-6 border-t border-line pt-8 text-sm sm:grid-cols-3">
            <div><dt className="eyebrow">Confirmation</dt><dd className="mt-1 font-mono text-base">{confirmationCode(r.id)}</dd></div>
            <div><dt className="eyebrow">Night of</dt><dd className="mt-1 text-base">{formatDate(r.stayDate, 'short')}</dd></div>
            <div><dt className="eyebrow">Guest</dt><dd className="mt-1 text-base">{r.guestName}</dd></div>
            <div><dt className="eyebrow">Hotel</dt><dd className="mt-1 text-base">{r.hotelName}</dd></div>
            <div><dt className="eyebrow">Room</dt><dd className="mt-1 text-base">{r.roomName}</dd></div>
            <div><dt className="eyebrow">{r.status === 'CONFIRMED' ? 'Paid' : 'Total'}</dt><dd className="mt-1 text-base">{formatMoney(r.total, r.currency)}</dd></div>
          </dl>

          <Cancellation r={r} onCancelled={res.reload} />

          <div className="mt-12 flex flex-wrap gap-8 text-sm">
            <TextLink to="/reservations">All your stays</TextLink>
            {r.status === 'RELEASED' && <TextLink to={`/hotels/${r.hotelId}?date=${r.stayDate}`}>Try booking again</TextLink>}
          </div>
        </div>
        <HotelPhoto hotelId={r.hotelId} date={r.stayDate} />
      </div>
    </Page>
  )
}

function HotelPhoto({ hotelId, date }: { hotelId: number; date: string }) {
  const hotel = useLoad(() => api.hotel(hotelId, date), [hotelId, date])
  const photo = hotel.data?.hotel.images[0]
  return (
    <div className="hidden lg:col-span-5 lg:block">
      {photo ? <Photo photo={photo} caption className="aspect-[4/5]" sizes="40vw" /> : <Skeleton className="aspect-[4/5]" />}
    </div>
  )
}
