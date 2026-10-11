import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api'

type LoadState<T> = { data: T | null; error: ApiError | null; loading: boolean; reload: () => void }

/** Loads data when `deps` change, ignoring responses that arrive after the inputs changed. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): LoadState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    setLoading(true)
    setError(null)
    load()
      .then((d) => current && setData(d))
      .catch((e) => current && setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e))))
      .finally(() => current && setLoading(false))
    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, attempt])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])
  return { data, error, loading, reload }
}
