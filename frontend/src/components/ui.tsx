import { motion } from 'motion/react'
import { AlertCircle, Loader2 } from 'lucide-react'
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router'

/** Fades a page in on arrival. MotionConfig in main.tsx turns this off for reduced motion. */
export function Page({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.main
      id="main"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
      className={`mx-auto w-full max-w-7xl px-5 sm:px-8 ${className}`}
    >
      {children}
    </motion.main>
  )
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: 'solid' | 'outline' }

export function Button({ busy, variant = 'solid', className = '', children, disabled, ...rest }: ButtonProps) {
  const look =
    variant === 'solid'
      ? 'bg-ink text-paper hover:bg-[#35302a] disabled:bg-muted'
      : 'border border-ink text-ink hover:bg-ink hover:text-paper disabled:border-line disabled:text-muted'
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex h-12 items-center justify-center gap-2 px-6 text-sm font-semibold tracking-wide transition-colors duration-200 disabled:cursor-not-allowed ${look} ${className}`}
    >
      {busy && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
      {children}
    </button>
  )
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }

export function Field({ label, error, hint, id, ...rest }: FieldProps) {
  const inputId = id ?? rest.name
  const describedBy = error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined
  return (
    <div>
      <label htmlFor={inputId} className="eyebrow mb-2 block">
        {label}
      </label>
      <input
        id={inputId}
        aria-invalid={!!error || undefined}
        aria-describedby={describedBy}
        {...rest}
        className={`h-12 w-full border-b bg-transparent px-0 text-base text-ink placeholder:text-muted/70 focus:outline-none focus-visible:outline-none focus:border-ink ${
          error ? 'border-rust' : 'border-line'
        }`}
      />
      {error ? (
        <p id={`${inputId}-error`} className="mt-1.5 text-sm text-rust">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${inputId}-hint`} className="mt-1.5 text-sm text-muted">
            {hint}
          </p>
        )
      )}
    </div>
  )
}

export function Notice({ tone = 'error', title, children }: { tone?: 'error' | 'info' | 'success'; title?: string; children: ReactNode }) {
  const styles = {
    error: 'border-rust/30 bg-rust-soft text-rust',
    info: 'border-line bg-card text-ink-soft',
    success: 'border-moss/30 bg-moss-soft text-moss',
  }[tone]
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`flex gap-3 border px-4 py-3 text-sm ${styles}`}>
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div>
        {title && <p className="font-semibold">{title}</p>}
        <div>{children}</div>
      </div>
    </div>
  )
}

/** Full-width message for empty results and failed loads. */
export function StateMessage({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="mx-auto max-w-lg py-24 text-center">
      <p className="display text-3xl">{title}</p>
      {children && <div className="mt-3 text-ink-soft">{children}</div>}
      {action && <div className="mt-8">{action}</div>}
    </div>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div aria-hidden className={`skeleton ${className}`} />
}

export function Stars({ count }: { count: number | null }) {
  if (!count) return null
  return (
    <span className="text-clay" aria-label={`${count}-star hotel`} title={`${count}-star hotel`}>
      {'★'.repeat(count)}
    </span>
  )
}

export function TextLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link to={to} className="link font-medium text-ink">
      {children}
    </Link>
  )
}
