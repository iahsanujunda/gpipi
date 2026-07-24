import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import * as authApi from './authApi'
import { onAuthExpired } from './authState'
import { AuthContext } from './useAuth'

export default function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const queryClient = useQueryClient()
  const authRevision = useRef(0)

  const clearSession = useCallback(() => {
    authRevision.current += 1
    queryClient.clear()
    setUser(null)
  }, [queryClient])

  useEffect(() => onAuthExpired(clearSession), [clearSession])

  useEffect(() => {
    let active = true
    const revision = authRevision.current
    authApi
      .getSession()
      .then((session) => {
        if (active && authRevision.current === revision) setUser(session)
      })
      .catch(() => {
        if (active && authRevision.current === revision) setUser(null)
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const redeem = useCallback(async (nonce) => {
    authRevision.current += 1
    const session = await authApi.redeem(nonce)
    setUser(session)
    setIsLoading(false)
    return session
  }, [])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      clearSession()
    }
  }, [clearSession])

  const value = useMemo(
    () => ({ user, isLoading, redeem, logout }),
    [user, isLoading, redeem, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
