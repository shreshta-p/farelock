import { AnimatePresence, motion } from 'motion/react'
import { Menu, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, ScrollRestoration, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth'

function Wordmark() {
  return (
    <Link to="/" className="group flex items-baseline gap-2" aria-label="FareLock home">
      <span className="display text-[1.65rem] leading-none tracking-tight">FareLock</span>
      <span className="eyebrow hidden translate-y-[-2px] sm:inline">Hotels</span>
    </Link>
  )
}

const navClass = ({ isActive }: { isActive: boolean }) =>
  `text-sm transition-colors hover:text-ink ${isActive ? 'text-ink' : 'text-ink-soft'}`

function Header() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)

  useEffect(() => setOpen(false), [location.pathname])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        menuButton.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  const signOut = async () => {
    await logout()
    navigate('/')
  }

  // Come back to the current page after signing in (but not to the login page itself).
  const onAuthPage = location.pathname === '/login' || location.pathname === '/register'
  const loginLink = onAuthPage ? '/login' : `/login?next=${encodeURIComponent(location.pathname + location.search)}`

  return (
    <header className="sticky top-0 z-30 border-b border-line/80 bg-paper/95 backdrop-blur-[2px]">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-3 focus:bg-ink focus:px-3 focus:py-2 focus:text-paper">
        Skip to content
      </a>
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-5 sm:px-8">
        <Wordmark />
        <nav aria-label="Main" className="hidden items-center gap-8 md:flex">
          <NavLink to="/search" className={navClass}>Hotels</NavLink>
          <NavLink to="/reservations" className={navClass}>My stays</NavLink>
          {user ? (
            <div className="flex items-center gap-5 border-l border-line pl-8">
              <span className="text-sm text-muted">{user.fullName.split(' ')[0]}</span>
              <button onClick={signOut} className="text-sm text-ink-soft transition-colors hover:text-ink">
                Sign out
              </button>
            </div>
          ) : (
            <Link to={loginLink} className="border border-ink px-4 py-2 text-sm font-medium transition-colors hover:bg-ink hover:text-paper">
              Sign in
            </Link>
          )}
        </nav>
        <button
          ref={menuButton}
          className="-mr-2 p-2 md:hidden"
          aria-expanded={open}
          aria-controls="mobile-menu"
          aria-label={open ? 'Close menu' : 'Open menu'}
          onClick={() => setOpen((o) => !o)}
        >
          {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </div>
      <AnimatePresence>
        {open && (
          <motion.nav
            id="mobile-menu"
            aria-label="Mobile"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
            className="overflow-hidden border-t border-line md:hidden"
          >
            <div className="flex flex-col px-5 py-4">
              <Link to="/search" className="display border-b border-line py-3 text-2xl">Hotels</Link>
              <Link to="/reservations" className="display border-b border-line py-3 text-2xl">My stays</Link>
              {user ? (
                <button onClick={signOut} className="py-4 text-left text-ink-soft">
                  Sign out <span className="text-muted">({user.email})</span>
                </button>
              ) : (
                <div className="flex gap-6 py-4">
                  <Link to={loginLink} className="link font-medium">Sign in</Link>
                  <Link to="/register" className="link font-medium">Create account</Link>
                </div>
              )}
            </div>
          </motion.nav>
        )}
      </AnimatePresence>
    </header>
  )
}

function Footer() {
  return (
    <footer className="mt-32 border-t border-line">
      <div className="mx-auto grid max-w-7xl gap-10 px-5 py-14 sm:px-8 md:grid-cols-[1.4fr_1fr_1fr]">
        <div>
          <p className="display text-2xl">FareLock</p>
          <p className="mt-3 max-w-sm text-sm leading-relaxed text-ink-soft">
            A small collection of remarkable hotels. The price you see is locked for fifteen minutes while you decide.
          </p>
        </div>
        <div className="text-sm text-ink-soft">
          <p className="eyebrow mb-3">Good to know</p>
          <ul className="space-y-2">
            <li>Bookings are for one night at a time.</li>
            <li>Payments are simulated. No card is ever charged.</li>
          </ul>
        </div>
        <div className="text-sm text-ink-soft">
          <p className="eyebrow mb-3">About this site</p>
          <p className="leading-relaxed">
            A portfolio demo, not affiliated with the hotels shown. Hotel facts come from Wikidata, and photos are credited on
            each page and in{' '}
            <a className="link" href="https://github.com/shreshta-p/farelock/blob/main/CREDITS.md" target="_blank" rel="noreferrer">
              CREDITS.md
            </a>
            .
          </p>
        </div>
      </div>
    </footer>
  )
}

export function Layout() {
  return (
    <div className="flex min-h-dvh flex-col">
      <Header />
      <div className="flex-1">
        <Outlet />
      </div>
      <Footer />
      <ScrollRestoration />
    </div>
  )
}
