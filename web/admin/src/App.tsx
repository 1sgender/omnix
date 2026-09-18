import { Navigate, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './state/auth'
import { AppShell } from './components/shell'
import { PageLoader } from './components/ui'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Health from './pages/Health'
import Licenses from './pages/Licenses'
import Users from './pages/Users'
import UserDetail from './pages/UserDetail'
import Devices from './pages/Devices'
import Subscriptions from './pages/Subscriptions'
import Providers from './pages/Providers'
import Usage from './pages/Usage'
import Logs from './pages/Logs'
import Audit from './pages/Audit'
import Settings from './pages/Settings'
import Features from './pages/Features'
import Admins from './pages/Admins'

function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <PageLoader />
  if (!me) return <Navigate to="/login" replace />
  return <AppShell>{children}</AppShell>
}

export default function App() {
  const { me, loading } = useAuth()

  return (
    <Routes>
      <Route
        path="/login"
        element={loading ? <PageLoader /> : me ? <Navigate to="/" replace /> : <Login />}
      />
      <Route path="/" element={<RequireAuth><Dashboard /></RequireAuth>} />
      <Route path="/health" element={<RequireAuth><Health /></RequireAuth>} />
      <Route path="/licenses" element={<RequireAuth><Licenses /></RequireAuth>} />
      <Route path="/users" element={<RequireAuth><Users /></RequireAuth>} />
      <Route path="/users/:id" element={<RequireAuth><UserDetail /></RequireAuth>} />
      <Route path="/devices" element={<RequireAuth><Devices /></RequireAuth>} />
      <Route path="/subscriptions" element={<RequireAuth><Subscriptions /></RequireAuth>} />
      <Route path="/providers" element={<RequireAuth><Providers /></RequireAuth>} />
      <Route path="/usage" element={<RequireAuth><Usage /></RequireAuth>} />
      <Route path="/logs" element={<RequireAuth><Logs /></RequireAuth>} />
      <Route path="/audit" element={<RequireAuth><Audit /></RequireAuth>} />
      <Route path="/settings" element={<RequireAuth><Settings /></RequireAuth>} />
      <Route path="/features" element={<RequireAuth><Features /></RequireAuth>} />
      <Route path="/admins" element={<RequireAuth><Admins /></RequireAuth>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
