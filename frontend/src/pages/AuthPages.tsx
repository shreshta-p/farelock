import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../api'
import { useAuth } from '../auth'
import { Photo } from '../components/Photo'
import { Button, Field, Notice, Page } from '../components/ui'
import { destinationPhotos } from '../lib/photos'

/** Only allow redirects back into this site, never to another origin. */
function safeNext(next: string | null) {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
}

function AuthLayout({ title, intro, photo, children }: { title: string; intro: string; photo: string; children: ReactNode }) {
  return (
    <Page className="pt-12">
      <div className="grid gap-14 lg:grid-cols-12 lg:items-center">
        <div className="lg:col-span-5">
          <h1 className="display text-5xl sm:text-6xl">{title}</h1>
          <p className="mt-4 text-ink-soft">{intro}</p>
          <div className="mt-10">{children}</div>
        </div>
        <div className="hidden lg:col-span-6 lg:col-start-7 lg:block">
          <Photo photo={photo} caption className="aspect-[4/5]" sizes="45vw" />
        </div>
      </div>
    </Page>
  )
}

export function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email, password)
      navigate(next, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not sign in')
      setBusy(false)
    }
  }

  return (
    <AuthLayout title="Welcome back" intro="Sign in to book and to see your stays." photo={destinationPhotos['Lisbon']}>
      <form onSubmit={submit} className="space-y-7">
        {error && <Notice>{error}</Notice>}
        <Field label="Email" name="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Password" name="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <Button type="submit" busy={busy} className="w-full">Sign in</Button>
        <p className="text-sm text-ink-soft">
          New to FareLock?{' '}
          <Link to={`/register?next=${encodeURIComponent(next)}`} className="link font-medium text-ink">Create an account</Link>
        </p>
      </form>
    </AuthLayout>
  )
}

export function Register() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const [form, setForm] = useState({ fullName: '', email: '', password: '' })
  const [errors, setErrors] = useState<Partial<Record<keyof typeof form, string>>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value })

  const validate = () => {
    const found: typeof errors = {}
    if (!form.fullName.trim()) found.fullName = 'Please enter your name.'
    if (!/^\S+@\S+\.\S+$/.test(form.email)) found.email = 'Please enter a valid email address.'
    if (form.password.length < 8) found.password = 'Use at least 8 characters.'
    else if (new TextEncoder().encode(form.password).length > 72) found.password = 'Use at most 72 characters.'
    setErrors(found)
    return Object.keys(found).length === 0
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!validate()) return
    setBusy(true)
    setError(null)
    try {
      await register(form.fullName, form.email, form.password)
      navigate(next, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create your account')
      setBusy(false)
    }
  }

  return (
    <AuthLayout title="Create an account" intro="One account for every booking. It takes a few seconds." photo={destinationPhotos['Kyoto']}>
      <form onSubmit={submit} className="space-y-7" noValidate>
        {error && <Notice>{error}</Notice>}
        <Field label="Full name" name="fullName" autoComplete="name" value={form.fullName} onChange={set('fullName')} error={errors.fullName} />
        <Field label="Email" name="email" type="email" autoComplete="email" value={form.email} onChange={set('email')} error={errors.email} />
        <Field
          label="Password"
          name="password"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={set('password')}
          error={errors.password}
          hint="At least 8 characters."
        />
        <Button type="submit" busy={busy} className="w-full">Create account</Button>
        <p className="text-sm text-ink-soft">
          Already have an account?{' '}
          <Link to={`/login?next=${encodeURIComponent(next)}`} className="link font-medium text-ink">Sign in</Link>
        </p>
      </form>
    </AuthLayout>
  )
}
