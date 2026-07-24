import { useEffect, useRef, useState } from 'react'
import { Box, ButtonBase, Stack } from '@mui/material'
import { Link, useLocation } from 'react-router'
import { ActivityIcon, BrandIcon, BudgetsIcon, CloseIcon } from './AppIcons'

const navigationItems = [
  { label: 'Budgets', to: '/budgets', icon: BudgetsIcon },
  { label: 'Activity', to: '/activity', icon: ActivityIcon },
]

function isCurrentPath(pathname, target) {
  return pathname === target || pathname.startsWith(`${target}/`)
}

export default function AppNavigation() {
  const [isOpen, setIsOpen] = useState(false)
  const launcherRef = useRef(null)
  const location = useLocation()

  function closeNavigation({ restoreFocus = true } = {}) {
    setIsOpen(false)
    if (restoreFocus) launcherRef.current?.focus()
  }

  useEffect(() => {
    if (!isOpen) return undefined

    function handleKeyDown(event) {
      if (event.key === 'Escape') closeNavigation()
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen])

  return (
    <>
      <Box
        aria-hidden="true"
        onClick={() => closeNavigation()}
        sx={(theme) => ({
          position: 'fixed',
          inset: 0,
          zIndex: theme.zIndex.modal,
          bgcolor: 'rgba(29, 78, 137, 0.30)',
          opacity: isOpen ? 1 : 0,
          pointerEvents: isOpen ? 'auto' : 'none',
          transition: `opacity ${isOpen ? 200 : 140}ms cubic-bezier(0.16, 1, 0.3, 1)`,
          '@media (prefers-reduced-motion: reduce)': { transitionDuration: '0ms' },
        })}
      />

      <Stack
        id="primary-navigation-menu"
        component="nav"
        aria-label="Primary"
        aria-hidden={!isOpen}
        spacing={1}
        sx={(theme) => ({
          position: 'fixed',
          insetInlineStart: '50%',
          bottom: 'calc(88px + env(safe-area-inset-bottom))',
          width: 'min(220px, calc(100vw - 32px))',
          transform: 'translateX(-50%)',
          zIndex: theme.zIndex.modal + 1,
          pointerEvents: isOpen ? 'auto' : 'none',
        })}
      >
        {navigationItems.map((item, index) => {
          const current = isCurrentPath(location.pathname, item.to)
          const Icon = item.icon
          const openDelay = (navigationItems.length - index - 1) * 30
          const closeDelay = index * 30

          return (
            <ButtonBase
              key={item.to}
              component={Link}
              to={item.to}
              tabIndex={isOpen ? 0 : -1}
              aria-current={current ? 'page' : undefined}
              onClick={() => closeNavigation({ restoreFocus: false })}
              sx={{
                minHeight: 48,
                px: 2.25,
                borderRadius: 999,
                justifyContent: 'flex-start',
                gap: 1.5,
                color: current ? 'primary.contrastText' : 'text.primary',
                bgcolor: current ? 'primary.main' : 'background.paper',
                boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
                fontSize: '1rem',
                fontWeight: current ? 700 : 650,
                opacity: isOpen ? 1 : 0,
                visibility: isOpen ? 'visible' : 'hidden',
                transform: isOpen
                  ? 'translate3d(0, 0, 0) scale(1)'
                  : 'translate3d(0, 16px, 0) scale(0.96)',
                transitionProperty: 'transform, opacity, visibility, background-color',
                transitionDuration: `${isOpen ? 200 : 140}ms`,
                transitionTimingFunction: 'cubic-bezier(0.16, 1, 0.3, 1)',
                transitionDelay: `${isOpen ? openDelay : closeDelay}ms`,
                '&:hover': {
                  bgcolor: current ? 'primary.dark' : 'background.paper',
                },
                '&:active': { transform: 'translate3d(0, 1px, 0) scale(0.98)' },
                '@media (prefers-reduced-motion: reduce)': {
                  transform: 'none',
                  transitionDuration: '0ms',
                  transitionDelay: '0ms',
                },
              }}
            >
              <Icon aria-hidden="true" sx={{ fontSize: 24, color: 'inherit', flex: '0 0 auto' }} />
              <Box component="span" sx={{ flexGrow: 1, textAlign: 'left' }}>{item.label}</Box>
              {current && (
                <Box
                  component="span"
                  aria-hidden="true"
                  sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'currentColor' }}
                />
              )}
            </ButtonBase>
          )
        })}
      </Stack>

      <Box
        data-testid="navigation-mask"
        sx={(theme) => ({
          position: 'fixed',
          insetInline: 0,
          bottom: 0,
          height: 'calc(72px + env(safe-area-inset-bottom))',
          bgcolor: 'background.default',
          zIndex: theme.zIndex.modal + 2,
        })}
      >
        <ButtonBase
          ref={launcherRef}
          aria-label={isOpen ? 'Close navigation' : 'Open navigation'}
          aria-expanded={isOpen}
          aria-controls="primary-navigation-menu"
          onClick={() => (isOpen ? closeNavigation() : setIsOpen(true))}
          sx={{
            '--brand-icon-accent': (theme) => theme.palette.brandAccent.main,
            position: 'absolute',
            insetBlockStart: 8,
            insetInlineStart: '50%',
            width: 56,
            height: 56,
            borderRadius: '50%',
            transform: 'translateX(-50%)',
            color: 'primary.contrastText',
            bgcolor: 'primary.main',
            boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
            transition: 'background-color 160ms ease, transform 160ms cubic-bezier(0.16, 1, 0.3, 1)',
            '&:hover': { bgcolor: 'primary.dark' },
            '&:active': { transform: 'translateX(-50%) scale(0.96)' },
            '@media (prefers-reduced-motion: reduce)': { transitionDuration: '0ms' },
          }}
        >
          {isOpen ? <CloseIcon sx={{ fontSize: 28 }} /> : <BrandIcon sx={{ fontSize: 30 }} />}
        </ButtonBase>
      </Box>
    </>
  )
}
