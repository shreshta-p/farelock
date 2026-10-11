import { motion } from 'motion/react'
import { ArrowUpRight } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { HotelCard, HotelCardSkeleton } from '../components/HotelCard'
import { Photo } from '../components/Photo'
import { SearchForm } from '../components/SearchForm'
import { Page } from '../components/ui'
import { tomorrow } from '../lib/format'
import { destinationPhotos, heroPhoto } from '../lib/photos'
import { useLoad } from '../lib/useLoad'

const rise = (delay: number) => ({
  initial: { opacity: 0, y: 14 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.7, delay, ease: [0.22, 1, 0.36, 1] as const },
})

function Hero() {
  return (
    <section className="pt-10 sm:pt-14">
      <div className="grid gap-10 lg:grid-cols-12 lg:items-end">
        <div className="relative z-10 lg:col-span-6 lg:pb-12">
          <motion.p {...rise(0)} className="eyebrow">Ten hotels · Six cities</motion.p>
          <motion.h1 {...rise(0.08)} className="display mt-5 text-[3rem] sm:text-[4.25rem] xl:text-[5rem]">
            Stay somewhere <em className="font-light italic text-clay">worth</em> remembering.
          </motion.h1>
          <motion.p {...rise(0.16)} className="mt-6 max-w-md text-lg leading-relaxed text-ink-soft">
            A short list of remarkable hotels, from a hillside retreat in Kyoto to a palace on the Left Bank. Choose a room and
            its price is locked for fifteen minutes while you decide.
          </motion.p>
          {/* On wide screens the search bar runs across the bottom edge of the photo. */}
          <motion.div {...rise(0.28)} className="mt-10 lg:mr-[-14rem]">
            <SearchForm />
          </motion.div>
        </div>
        <motion.div
          initial={{ opacity: 0, scale: 1.02 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 1.1, ease: [0.22, 1, 0.36, 1] }}
          className="lg:col-span-6"
        >
          <Photo photo={heroPhoto} priority caption className="aspect-[4/3] lg:aspect-[4/5]" sizes="(min-width: 1024px) 50vw, 100vw" />
        </motion.div>
      </div>
    </section>
  )
}

function CityIndex() {
  const destinations = useLoad(() => api.destinations(), [])
  const cities = destinations.data ?? []
  const [active, setActive] = useState<string | null>(null)
  const shown = active ?? cities[0]?.city

  return (
    <section aria-labelledby="cities" className="mt-32 grid gap-12 lg:grid-cols-12">
      <div className="lg:col-span-5">
        <p className="eyebrow">The cities</p>
        <h2 id="cities" className="display mt-4 text-5xl">Where we stay</h2>
        <ol className="mt-10 border-t border-line">
          {cities.map((d, i) => (
            <li key={d.city} className="border-b border-line">
              <Link
                to={`/search?destination=${encodeURIComponent(d.city)}&date=${tomorrow()}`}
                onMouseEnter={() => setActive(d.city)}
                onFocus={() => setActive(d.city)}
                className="group flex items-baseline gap-5 py-5"
              >
                <span className="w-7 text-xs font-semibold tabular-nums text-clay">{String(i + 1).padStart(2, '0')}</span>
                <span className="display flex-1 text-3xl transition-transform duration-500 ease-[var(--ease-out-soft)] group-hover:translate-x-2 sm:text-4xl">
                  {d.city}
                </span>
                <span className="hidden text-sm text-muted sm:inline">
                  {d.country} · {d.hotelCount} {d.hotelCount === 1 ? 'hotel' : 'hotels'}
                </span>
                <ArrowUpRight className="h-4 w-4 text-muted transition-colors group-hover:text-ink" aria-hidden />
              </Link>
            </li>
          ))}
          {destinations.loading && [0, 1, 2, 3].map((i) => <li key={i} className="skeleton my-5 h-9" />)}
        </ol>
      </div>
      <div className="relative hidden lg:col-span-6 lg:col-start-7 lg:block">
        <div className="sticky top-24">
          {shown && destinationPhotos[shown] && (
            <motion.div key={shown} initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.5 }}>
              <Photo photo={destinationPhotos[shown]} caption className="aspect-[4/5]" sizes="45vw" alt={shown} />
            </motion.div>
          )}
        </div>
      </div>
    </section>
  )
}

function Featured() {
  const date = tomorrow()
  const hotels = useLoad(() => api.searchHotels('', date), [date])
  return (
    <section aria-labelledby="featured" className="mt-32">
      <div className="flex items-end justify-between gap-6 border-b border-line pb-6">
        <div>
          <p className="eyebrow">Available tomorrow night</p>
          <h2 id="featured" className="display mt-4 text-5xl">Selected stays</h2>
        </div>
        <Link to={`/search?date=${date}`} className="link hidden text-sm font-medium sm:inline">
          See all hotels
        </Link>
      </div>
      <div className="mt-12 grid gap-x-10 gap-y-16 md:grid-cols-2">
        {hotels.loading && [0, 1].map((i) => <HotelCardSkeleton key={i} />)}
        {hotels.data?.slice(0, 4).map((s, i) => <HotelCard key={s.hotel.id} summary={s} date={date} index={i} />)}
      </div>
      {hotels.error && <p className="mt-8 text-ink-soft">Hotels could not be loaded right now. Please try again shortly.</p>}
    </section>
  )
}

function HowItWorks() {
  const steps = [
    ['Lock the price', 'Choosing a room creates a quote. Its price is fixed for fifteen minutes.'],
    ['Hold the room', 'Reserving takes the room off the market for ten minutes while you pay.'],
    ['Pay once', 'Retries and double clicks are safe. You are never charged twice.'],
  ]
  return (
    <section aria-labelledby="how" className="mt-32 bg-paper-deep/60 px-6 py-14 sm:px-12">
      <h2 id="how" className="display text-3xl">How booking works</h2>
      <ol className="mt-10 grid gap-10 md:grid-cols-3">
        {steps.map(([title, text], i) => (
          <li key={title} className="border-t border-ink/70 pt-5">
            <p className="text-xs font-semibold tabular-nums text-clay">{String(i + 1).padStart(2, '0')}</p>
            <p className="display mt-3 text-2xl">{title}</p>
            <p className="mt-2 text-sm leading-relaxed text-ink-soft">{text}</p>
          </li>
        ))}
      </ol>
    </section>
  )
}

export function Home() {
  return (
    <Page>
      <Hero />
      <CityIndex />
      <Featured />
      <HowItWorks />
    </Page>
  )
}
