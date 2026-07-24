import { createContext, useContext, useEffect } from 'react'

export const PageActionsContext = createContext(null)

export function usePageActions() {
  const context = useContext(PageActionsContext)
  if (!context) throw new Error('usePageActions must be used inside PageActionsProvider')
  return context
}

export function usePageAction(action) {
  const { registerPageAction } = usePageActions()

  useEffect(
    () => registerPageAction(action),
    [action, registerPageAction],
  )
}

export function useNavigationGuard(guard) {
  const { registerNavigationGuard } = usePageActions()

  useEffect(() => {
    if (!guard) return undefined
    return registerNavigationGuard(guard)
  }, [guard, registerNavigationGuard])
}
