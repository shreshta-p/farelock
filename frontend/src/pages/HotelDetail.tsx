import { ArrowLeft, BedDouble, Maximize2, Users } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, ApiError, type Hotel, type RoomOffer } from '../api'
import { useAuth } from '../auth'
import { Photo } from '../components/Photo'
import { Button, Notice, Page, Skeleton, StateMessage, Stars, TextLink } from '../components/ui'
import { formatDate, formatMoney, isBookableDate, lastBookableDate, today, tomorrow } from '../lib/format'
import { creditFor } from '../lib/photos'
import { useLoad } from '../lib/useLoad'

function Gallery({ hotel }: { hotel: Hotel }) {
  const [lead, ...rest] = hotel.images
  return (
    <section aria-label="Photos">
      {/* Lead photo on the left, three smaller ones on the right; only the lead on phones. */}
      <div className="grid gap-2 sm:h-[30rem] sm:grid-cols-4 sm:grid-rows-2 lg:h-[36rem]">
        <Photo
          photo={lead}
          priority
          alt={`${hotel.name}, exterior`}
          figureClassName="sm:col-span-2 sm:row-span-2"
          className="aspect-[4/3] sm:aspect-auto sm:h-full"
          sizes="(min-width: 640px) 50vw, 100vw"
        />
        {rest.map((photo, i) => (
          <Photo
            key={photo}
            photo={photo}
            figureClassName={`hidden sm:block ${i === 0 ? 'sm:col-span-2' : ''}`}
            className="h-full"
            sizes={i === 0 ? '50vw' : '25vw'}
          />
        ))}
      </div>
      <p className="mt-3 text-[0.7rem] leading-relaxed text-muted">
        Exterior: {creditFor(lead)?.author} ({creditFor(lead)?.license}, Wikimedia Commons). Interior and city photos are
        representative ({rest.map((p) => creditFor(p)?.author).join(', ')} on Unsplash).
      </p>
    </section>
  )
}

function RoomRow({ offer, onReserve, busy, error }: { offer: RoomOffer; onReserve: () => void; busy: boolean; error?: string }) {
  const { room, available, price } = offer
  const soldOut = available === 0
  return (
    <li className="grid gap-6 border-t border-line py-8 md:grid-cols-[minmax(0,280px)_1fr_auto] md:gap-10">
      {room.image && <Photo photo={room.image} className="aspect-[4/3]" sizes="(min-width: 768px) 280px, 100vw" alt={room.name} />}
      <div>
        <h3 className="display text-[1.7rem]">{room.name}</h3>
        <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-sm text-muted">
          <li className="flex items-center gap-1.5"><BedDouble className="h-4 w-4" aria-hidden />{room.bed}</li>
          <li className="flex items-center gap-1.5"><Maximize2 className="h-3.5 w-3.5" aria-hidden />{room.sizeSqm} m²</li>
          <li className="flex items-center gap-1.5"><Users className="h-4 w-4" aria-hidden />Up to {room.maxGuests}</li>
        </ul>
        <p className="mt-4 max-w-prose leading-relaxed text-ink-soft">{room.description}</p>
        {error && <div className="mt-4"><Notice>{error}</Notice></div>}
      </div>
      <div className="flex flex-row items-end justify-between gap-4 md:flex-col md:items-end md:justify-start md:text-right">
        <div>
          {price ? (
            <>
              <p className="display text-3xl">{formatMoney(price.amount, price.currency)}</p>
              <p className="text-xs text-muted">per night</p>
            </>
          ) : (
            <p className="text-sm text-muted">Price unavailable</p>
          )}
          <p className={`mt-2 text-sm ${available > 0 && available <= 3 ? 'font-medium text-clay' : 'text-muted'}`}>
            {soldOut ? 'Fully booked this night' : available <= 3 ? `Only ${available} left` : `${available} available`}
          </p>
        </div>
        <Button onClick={onReserve} busy={busy} disabled={soldOut || !price} className="md:mt-4 md:w-40">
          {soldOut ? 'Sold out' : 'Reserve'}
        </Button>
      </div>
    </li>
  )
}

export function HotelDetail() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const date = isBookableDate(params.get('date')) ? params.get('date')! : tomorrow()
  const detail = useLoad(() => api.hotel(id, date), [id, date])
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [reserving, setReserving] = useState<number | null>(null)
  const [roomErrors, setRoomErrors] = useState<Record<number, string>>({})

  // Choosing a room asks the Pricing Service for a quote: a price snapshot valid for 15 minutes.
  const reserve = async (roomTypeId: number) => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
      return
    }
    setReserving(roomTypeId)
    setRoomErrors({})
    try {
      const quote = await api.createQuote(roomTypeId, date)
      navigate(`/checkout/${quote.id}`)
    } catch (e) {
      setRoomErrors({ [roomTypeId]: e instanceof ApiError ? e.message : 'Could not start your booking' })
      setReserving(null)
    }
  }

  if (detail.error) {
    return (
      <Page>
        <StateMessage
          title={detail.error.status === 404 ? 'Hotel not found' : "We couldn't load this hotel"}
          action={<TextLink to="/search">Browse all hotels</TextLink>}
        >
          {detail.error.status !== 404 && detail.error.message}
        </StateMessage>
      </Page>
    )
  }

  const data = detail.data
  return (
    <Page className="pt-8">
      <Link to={`/search?date=${date}`} className="inline-flex items-center gap-2 text-sm text-ink-soft hover:text-ink">
        <ArrowLeft className="h-4 w-4" aria-hidden /> All hotels
      </Link>

      {!data ? (
        <div className="mt-8">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="mt-4 h-14 w-2/3" />
          <Skeleton className="mt-10 aspect-[16/7]" />
        </div>
      ) : (
        <>
          <header className="mt-8 flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <p className="eyebrow">{data.hotel.city}, {data.hotel.country}</p>
              <h1 className="display mt-4 text-5xl sm:text-6xl lg:text-7xl">{data.hotel.name}</h1>
            </div>
            <p className="text-sm text-ink-soft lg:text-right">
              <Stars count={data.hotel.starRating} />
              <br />
              {data.hotel.address}
            </p>
          </header>

          <div className="mt-10">
            <Gallery hotel={data.hotel} />
          </div>

          <div className="mt-16 grid gap-12 lg:grid-cols-12">
            <div className="lg:col-span-7">
              <p className="display text-2xl leading-snug text-ink sm:text-[1.75rem]">{data.hotel.description}</p>
              <h2 className="eyebrow mt-10">At the hotel</h2>
              <ul className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-ink-soft">
                {data.hotel.amenities.map((a) => <li key={a} className="border-b border-line pb-2">{a}</li>)}
              </ul>
              {data.hotel.sourceUrl && (
                <p className="mt-6 text-xs text-muted">
                  Hotel name and location from{' '}
                  <a href={data.hotel.sourceUrl} className="link" target="_blank" rel="noreferrer">Wikidata</a>. Rooms and prices are
                  FareLock demo data.
                </p>
              )}
            </div>
            <aside className="lg:col-span-4 lg:col-start-9">
              <div className="border border-line bg-card p-6">
                <label htmlFor="night" className="eyebrow">Your night</label>
                <input
                  id="night"
                  type="date"
                  value={date}
                  min={today()}
                  max={lastBookableDate()}
                  onChange={(e) => isBookableDate(e.target.value) && setParams({ date: e.target.value }, { replace: true })}
                  className="mt-2 h-11 w-full cursor-pointer border-b border-line bg-transparent text-lg focus:border-ink focus:outline-none"
                />
                <p className="mt-3 text-sm text-ink-soft">{formatDate(date)}. Check in from 3pm, check out by 11am.</p>
                <p className="mt-1 text-xs text-muted">FareLock books one night per reservation.</p>
              </div>
            </aside>
          </div>

          <section aria-labelledby="rooms" className="mt-20">
            <div className="flex items-end justify-between">
              <h2 id="rooms" className="display text-4xl">Rooms</h2>
              <p className="text-sm text-muted">{formatDate(date, 'short')}</p>
            </div>
            {!data.pricesAvailable && (
              <div className="mt-6">
                <Notice tone="info" title="Prices are temporarily unavailable">
                  You can still browse rooms. Reserving will be possible again in a moment.
                </Notice>
              </div>
            )}
            <ul className="mt-8 border-b border-line">
              {data.rooms.map((offer) => (
                <RoomRow
                  key={offer.room.id}
                  offer={offer}
                  busy={reserving === offer.room.id}
                  error={roomErrors[offer.room.id]}
                  onReserve={() => reserve(offer.room.id)}
                />
              ))}
            </ul>
          </section>
        </>
      )}
    </Page>
  )
}
