/**
 * Auth-контекст: токен в sessionStorage (умирает с табом — компромисс
 * UX/безопасности для админки), профиль и права из /me, роли в UI.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { endpoints } from '../api/endpoints'
import { getToken, setToken } from '../api/client'
import type { AdminPermission, MeResponse } from '../api/types'

interface AuthState {
  me: MeResponse | null
  loading: boolean
  can: (permission: AdminPermission) => boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<MeResponse | null>(null)
  const [loading, setLoading] = useState<boolean>(() => getToken() !== null)

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setMe(null)
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      setMe(await endpoints.me())
    } catch {
      setToken(null)
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const login = useCallback(async (username: string, password: string) => {
    const response = await endpoints.login(username, password)
    setToken(response.token)
    setMe(await endpoints.me())
  }, [])

  const logout = useCallback(async () => {
    try {
      await endpoints.logout()
    } catch {
      /* сервер мог уже забыть сессию — локально чистим в любом случае */
    }
    setToken(null)
    setMe(null)
  }, [])

  const can = useCallback(
    (permission: AdminPermission) => me?.permissions.includes(permission) ?? false,
    [me]
  )

  const value = useMemo<AuthState>(
    () => ({ me, loading, can, login, logout, refresh }),
    [me, loading, can, login, logout, refresh]
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth вне AuthProvider')
  return ctx
}
