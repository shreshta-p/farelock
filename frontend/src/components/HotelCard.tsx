import { Link } from 'react-router'
import type { HotelSummary } from '../api'
import { formatMoney } from '../lib/format'
import { Photo } from './Photo'
import { Skeleton, Stars } from './ui'

/** A hotel as a magazine entry: a big photo, then name, place and price on hairline rules. */
export function HotelCard({ summary, date, index }: { summary: HotelSummary; date: string; index?: number }) {
  const { hotel, fromPrice, roomsAvailable } = summary
  const soldOut = roomsAvailable === 0
  return (
    <article className="group">
      <Link to={`/hotels/${hotel.id}?date=${date}`} className="block focus-visible:outline-offset-4">
        <Photo photo={hotel.images[0]} alt={`${hotel.name}, ${hotel.city}`} className="aspect-[4/3]" sizes="(min-width: 1024px) 45vw, 100vw" />
        <div className="mt-5 flex items-start justify-between gap-6">
          <div>
            <p className="eyebrow">
              {index !== undefined && <span className="mr-3 text-clay">{String(index + 1).padStart(2, '0')}</span>}
              {hotel.city}, {hotel.country}
            </p>
            <h3 className="display mt-2 text-[1.75rem] transition-colors group-hover:text-clay">{hotel.name}</h3>
            <p className="mt-1 text-sm">
              <Stars count={hotel.starRating} />
            </p>
          </div>
          <div className="shrink-0 pt-6 text-right">
            {soldOut ? (
              <p className="text-sm text-muted">Fully booked</p>
            ) : fromPrice ? (
              <>
                <p className="text-xs text-muted">from</p>
                <p className="display text-2xl">{formatMoney(fromPrice.amount, fromPrice.currency)}</p>
                <p className="text-xs text-muted">per night</p>
              </>
            ) : (
              <p className="text-sm text-muted">Price on request</p>
            )}
          </div>
        </div>
      </Link>
    </article>
  )
}

export function HotelCardSkeleton() {
  return (
    <div>
      <Skeleton className="aspect-[4/3]" />
      <Skeleton className="mt-5 h-3 w-32" />
      <Skeleton className="mt-3 h-7 w-56" />
    </div>
  )
}
