import { useCallback, useMemo, useState } from 'react'
import { PageActionsContext } from './pageActions'

export function PageActionsProvider({ children }) {
  const [pageActions, setPageActions] = useState([])
  const [navigationGuard, setNavigationGuard] = useState(null)

  const registerPageAction = useCallback((action) => {
    setPageActions((current) => {
      const existingIndex = current.findIndex((item) => item.id === action.id)
      if (existingIndex === -1) return [...current, action]
      return current.map((item, index) => (index === existingIndex ? action : item))
    })
    return () => {
      setPageActions((current) => current.filter((item) => item.id !== action.id))
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
      pageActions,
      registerNavigationGuard,
      registerPageAction,
    }),
    [navigationGuard, pageActions, registerNavigationGuard, registerPageAction],
  )

  return (
    <PageActionsContext.Provider value={value}>
      {children}
    </PageActionsContext.Provider>
  )
}
