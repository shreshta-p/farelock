import { ArrowRight } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import { lastBookableDate, today, tomorrow } from '../lib/format'
import { useLoad } from '../lib/useLoad'

type Props = { initialDestination?: string; initialDate?: string; compact?: boolean }

/** Destination + night. One night only, and the form says so. */
export function SearchForm({ initialDestination = '', initialDate, compact }: Props) {
  const navigate = useNavigate()
  const destinations = useLoad(() => api.destinations(), [])
  const [destination, setDestination] = useState(initialDestination)
  const [date, setDate] = useState(initialDate ?? tomorrow())

  const submit = (e: FormEvent) => {
    e.preventDefault()
    navigate(`/search?destination=${encodeURIComponent(destination)}&date=${date}`)
  }

  const cell = compact ? 'py-2' : 'py-4'
  return (
    <form
      onSubmit={submit}
      role="search"
      aria-label="Find a hotel"
      className="grid border border-ink/80 bg-card sm:grid-cols-[1.3fr_1fr_auto]"
    >
      <label className={`flex flex-col border-b border-line px-5 sm:border-b-0 sm:border-r ${cell}`}>
        <span className="eyebrow">Destination</span>
        <select
          value={destination}
          onChange={(e) => setDestination(e.target.value)}
          className="mt-1 -ml-1 h-9 cursor-pointer bg-transparent text-base text-ink focus:outline-none"
        >
          <option value="">All destinations</option>
          {destinations.data?.map((d) => (
            <option key={d.city} value={d.city}>
              {d.city}, {d.country}
            </option>
          ))}
        </select>
      </label>
      <label className={`flex flex-col px-5 ${cell}`}>
        <span className="eyebrow">
          Night of <span className="font-normal normal-case tracking-normal text-muted">(one-night stays)</span>
        </span>
        <input
          type="date"
          required
          value={date}
          min={today()}
          max={lastBookableDate()}
          onChange={(e) => setDate(e.target.value)}
          className="mt-1 h-9 cursor-pointer bg-transparent text-base text-ink focus:outline-none"
        />
      </label>
      <button
        type="submit"
        className="group inline-flex items-center justify-center gap-3 bg-ink px-8 py-4 text-sm font-semibold tracking-wide text-paper transition-colors hover:bg-[#35302a]"
      >
        Search
        <ArrowRight className="h-4 w-4 transition-transform duration-300 group-hover:translate-x-1" aria-hidden />
      </button>
    </form>
  )
}
