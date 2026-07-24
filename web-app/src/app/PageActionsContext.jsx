import { useCallback, useMemo, useState } from 'react'
import { PageActionsContext } from './pageActions'

export function PageActionsProvider({ children }) {
  const [pageAction, setPageAction] = useState(null)
  const [navigationGuard, setNavigationGuard] = useState(null)

  const registerPageAction = useCallback((action) => {
    setPageAction(action)
    return () => {
      setPageAction((current) => (current?.id === action.id ? null : current))
    }
  }, [])

  const registerNavigationGuard = useCallback((guard) => {
    setNavigationGuard(() => guard)
    return () => {
      setNavigationGuard((current) => (current === guard ? null : current))
    }
  }, [])

  const value = useMemo(
    () => ({
      navigationGuard,
      pageAction,
      registerNavigationGuard,
      registerPageAction,
    }),
    [navigationGuard, pageAction, registerNavigationGuard, registerPageAction],
  )

  return (
    <PageActionsContext.Provider value={value}>
      {children}
    </PageActionsContext.Provider>
  )
}
