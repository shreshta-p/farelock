import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router'
import { api, type Reservation } from '../api'
import { Button, Page, Skeleton, StateMessage, TextLink } from '../components/ui'
import { formatDate, formatMoney, today } from '../lib/format'
import { useLoad } from '../lib/useLoad'

const STATUS: Record<Reservation['status'], { label: string; className: string }> = {
  CONFIRMED: { label: 'Confirmed', className: 'bg-moss-soft text-moss' },
  PAYMENT_PENDING: { label: 'Verifying payment', className: 'bg-clay-soft text-clay' },
  HELD: { label: 'Awaiting payment', className: 'bg-clay-soft text-clay' },
  RELEASED: { label: 'Released', className: 'bg-paper-deep text-muted' },
}

function Row({ r }: { r: Reservation }) {
  const status = STATUS[r.status]
  return (
    <li>
      <Link
        to={`/reservations/${r.id}`}
        className="group grid gap-2 border-b border-line py-6 transition-colors hover:bg-card/60 sm:grid-cols-[160px_1fr_auto_auto] sm:items-center sm:gap-8 sm:px-3"
      >
        <span className="text-sm text-ink-soft">{formatDate(r.stayDate, 'short')}</span>
        <span>
          <span className="display block text-2xl">{r.hotelName}</span>
          <span className="text-sm text-muted">{r.roomName} · {r.guestName}</span>
        </span>
        <span className={`w-fit px-2.5 py-1 text-xs font-semibold ${status.className}`}>{status.label}</span>
        <span className="flex items-center gap-4 sm:justify-end">
          <span className="tabular-nums">{formatMoney(r.amount, r.currency)}</span>
          <ArrowRight className="h-4 w-4 text-muted transition-transform group-hover:translate-x-1" aria-hidden />
        </span>
      </Link>
    </li>
  )
}

export function Reservations() {
  const list = useLoad(() => api.reservations(), [])
  const all = list.data ?? []
  const upcoming = all.filter((r) => r.stayDate >= today() && r.status !== 'RELEASED')
  const past = all.filter((r) => !upcoming.includes(r))

  return (
    <Page className="pt-12">
      <p className="eyebrow">Your account</p>
      <h1 className="display mt-4 text-5xl sm:text-6xl">My stays</h1>

      {list.error ? (
        <StateMessage title="We couldn't load your stays" action={<Button onClick={list.reload}>Try again</Button>} />
      ) : list.loading ? (
        <div className="mt-12 space-y-4">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-20" />)}</div>
      ) : all.length === 0 ? (
        <StateMessage title="No stays yet" action={<TextLink to="/search">Find a hotel</TextLink>}>
          When you book a room it will appear here.
        </StateMessage>
      ) : (
        <>
          <section aria-labelledby="upcoming" className="mt-14">
            <h2 id="upcoming" className="eyebrow border-b border-ink pb-3">Upcoming</h2>
            {upcoming.length ? <ul>{upcoming.map((r) => <Row key={r.id} r={r} />)}</ul> : <p className="py-6 text-ink-soft">Nothing booked ahead.</p>}
          </section>
          {past.length > 0 && (
            <section aria-labelledby="past" className="mt-14">
              <h2 id="past" className="eyebrow border-b border-ink pb-3">Past and released</h2>
              <ul>{past.map((r) => <Row key={r.id} r={r} />)}</ul>
            </section>
          )}
        </>
      )}
    </Page>
  )
}
