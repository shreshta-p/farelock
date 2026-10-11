import { MotionConfig } from 'motion/react'
import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider, useLocation } from 'react-router'
import { AuthProvider, useAuth } from './auth'
import { Layout } from './components/Layout'
import { Page, StateMessage, TextLink } from './components/ui'
import './index.css'
import { Login, Register } from './pages/AuthPages'
import { Checkout } from './pages/Checkout'
import { Home } from './pages/Home'
import { HotelDetail } from './pages/HotelDetail'
import { ReservationPage } from './pages/ReservationPage'
import { Reservations } from './pages/Reservations'
import { Search } from './pages/Search'

/** Sends signed-out visitors to the login page, then back here afterwards. */
function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) return null
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  return children
}

function NotFound() {
  return (
    <Page>
      <StateMessage title="Page not found" action={<TextLink to="/">Back to FareLock</TextLink>} />
    </Page>
  )
}

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/search', element: <Search /> },
      { path: '/hotels/:id', element: <HotelDetail /> },
      { path: '/login', element: <Login /> },
      { path: '/register', element: <Register /> },
      { path: '/checkout/:quoteId', element: <RequireAuth><Checkout /></RequireAuth> },
      { path: '/reservations', element: <RequireAuth><Reservations /></RequireAuth> },
      { path: '/reservations/:id', element: <RequireAuth><ReservationPage /></RequireAuth> },
      { path: '*', element: <NotFound /> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* reducedMotion="user": animations are skipped for people who ask their OS for less motion. */}
    <MotionConfig reducedMotion="user">
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </MotionConfig>
  </StrictMode>,
)
