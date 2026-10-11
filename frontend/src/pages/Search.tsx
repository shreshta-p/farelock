import { useSearchParams } from 'react-router'
import { api } from '../api'
import { HotelCard, HotelCardSkeleton } from '../components/HotelCard'
import { SearchForm } from '../components/SearchForm'
import { Button, Page, StateMessage, TextLink } from '../components/ui'
import { formatDate, isBookableDate, tomorrow } from '../lib/format'
import { useLoad } from '../lib/useLoad'

export function Search() {
  const [params] = useSearchParams()
  const destination = params.get('destination') ?? ''
  const rawDate = params.get('date')
  const date = isBookableDate(rawDate) ? rawDate : tomorrow()
  const results = useLoad(() => api.searchHotels(destination, date), [destination, date])

  const count = results.data?.length ?? 0
  return (
    <Page className="pt-12">
      <header className="grid gap-8 border-b border-line pb-10 lg:grid-cols-[1fr_auto] lg:items-end">
        <div>
          <p className="eyebrow">{formatDate(date)} · one night</p>
          <h1 className="display mt-4 text-5xl sm:text-6xl">{destination ? `Hotels in ${destination}` : 'All hotels'}</h1>
          {!results.loading && results.data && (
            <p className="mt-3 text-ink-soft">
              {count} {count === 1 ? 'hotel' : 'hotels'}
            </p>
          )}
        </div>
        <div className="lg:w-[640px]">
          <SearchForm key={`${destination}-${date}`} initialDestination={destination} initialDate={date} compact />
        </div>
      </header>

      {rawDate && rawDate !== date && (
        <p className="mt-6 text-sm text-ink-soft">
          That date is outside our booking window, so we're showing {formatDate(date)} instead.
        </p>
      )}

      {results.error ? (
        <StateMessage title="We couldn't load hotels" action={<Button onClick={results.reload}>Try again</Button>}>
          {results.error.message}
        </StateMessage>
      ) : !results.loading && count === 0 ? (
        <StateMessage title="No hotels here yet" action={<TextLink to={`/search?date=${date}`}>See every hotel</TextLink>}>
          We don't have a stay in that destination. Try another city.
        </StateMessage>
      ) : (
        <div className="mt-14 grid gap-x-10 gap-y-20 md:grid-cols-2">
          {results.loading && [0, 1, 2, 3].map((i) => <HotelCardSkeleton key={i} />)}
          {results.data?.map((s, i) => <HotelCard key={s.hotel.id} summary={s} date={date} index={i} />)}
        </div>
      )}
    </Page>
  )
}
