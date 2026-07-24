import { useEffect, useRef, useState } from 'react'
import { Box, ButtonBase, Stack, Typography } from '@mui/material'
import { Link, useLocation, useNavigate } from 'react-router'
import {
  ActivityIcon,
  AddIcon,
  BrandIcon,
  BudgetsIcon,
  CloseIcon,
} from './AppIcons'
import { usePageActions } from './pageActions'

const OPEN_DURATION = 200
const CLOSE_DURATION = 140
const STAGGER = 30
const LAUNCHER_ICON_DURATION = 180

const navigationItems = [
  { label: 'Budgets', to: '/budgets', icon: BudgetsIcon },
  { label: 'Activity', to: '/activity', icon: ActivityIcon },
]

function isCurrentPath(pathname, target) {
  return pathname === target || pathname.startsWith(`${target}/`)
}

function entryMotion(isOpen, visualIndex, entryCount) {
  const openDelay = (entryCount - visualIndex - 1) * STAGGER
  const closeDelay = visualIndex * STAGGER

  return {
    opacity: isOpen ? 1 : 0,
    visibility: isOpen ? 'visible' : 'hidden',
    transform: isOpen
      ? 'translate3d(0, 0, 0) scale(1)'
      : 'translate3d(0, 16px, 0) scale(0.96)',
    transitionProperty: 'transform, opacity, visibility, background-color',
    transitionDuration: `${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms`,
    transitionTimingFunction: 'cubic-bezier(0.16, 1, 0.3, 1)',
    transitionDelay: `${isOpen ? openDelay : closeDelay}ms`,
    '@media (prefers-reduced-motion: reduce)': {
      transform: 'none',
      transitionDuration: '0ms',
      transitionDelay: '0ms',
    },
  }
}

export default function AppNavigation() {
  const [isOpen, setIsOpen] = useState(false)
  const launcherRef = useRef(null)
  const location = useLocation()
  const navigate = useNavigate()
  const { navigationGuard, pageAction } = usePageActions()
  const PageActionIcon = pageAction?.icon ?? AddIcon
  const entryCount = navigationItems.length + (pageAction ? 1 : 0)

  function closeNavigation({ restoreFocus = true } = {}) {
    setIsOpen(false)
    if (restoreFocus) launcherRef.current?.focus()
  }

  function selectDestination(event, target) {
    event.preventDefault()
    closeNavigation({ restoreFocus: false })

    if (isCurrentPath(location.pathname, target)) return

    const continueNavigation = () => navigate(target)
    if (navigationGuard) {
      navigationGuard(continueNavigation)
      return
    }
    continueNavigation()
  }

  function selectPageAction() {
    closeNavigation({ restoreFocus: false })
    pageAction?.onSelect()
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
          transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms cubic-bezier(0.16, 1, 0.3, 1)`,
          '@media (prefers-reduced-motion: reduce)': { transitionDuration: '0ms' },
        })}
      />

      <Box
        id="primary-navigation-menu"
        aria-hidden={!isOpen}
        data-menu-state={isOpen ? 'open' : 'closed'}
        sx={(theme) => ({
          position: 'fixed',
          insetInlineStart: '50%',
          bottom: 'calc(88px + env(safe-area-inset-bottom))',
          width: 'min(268px, calc(100vw - 32px))',
          transform: 'translateX(-50%)',
          zIndex: theme.zIndex.modal + 1,
          pointerEvents: isOpen ? 'auto' : 'none',
        })}
      >
        {pageAction && (
          <Box
            component="section"
            aria-labelledby="page-action-label"
            sx={{ mb: 2.25 }}
          >
            <ButtonBase
              data-menu-entry="page-action"
              data-enter-duration-ms={OPEN_DURATION}
              data-exit-duration-ms={CLOSE_DURATION}
              tabIndex={isOpen ? 0 : -1}
              onClick={selectPageAction}
              sx={{
                ...entryMotion(isOpen, 0, entryCount),
                width: '100%',
                minHeight: 66,
                px: 1.5,
                border: 2,
                borderColor: 'brandAccent.main',
                borderRadius: 3.5,
                justifyContent: 'flex-start',
                gap: 1.5,
                color: 'text.heading',
                bgcolor: 'background.paper',
                boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
                textAlign: 'left',
                '&:hover': { bgcolor: 'background.paper' },
                '&:active': { transform: 'translate3d(0, 1px, 0) scale(0.99)' },
              }}
            >
              <Box
                sx={{
                  display: 'grid',
                  placeItems: 'center',
                  width: 38,
                  height: 38,
                  flex: '0 0 auto',
                  borderRadius: '50%',
                  color: 'primary.contrastText',
                  bgcolor: 'primary.main',
                }}
              >
                <PageActionIcon aria-hidden="true" sx={{ fontSize: 24 }} />
              </Box>
              <Stack spacing={0.125}>
                <Typography
                  id="page-action-label"
                  component="span"
                  sx={{
                    color: 'primary.main',
                    fontSize: '0.625rem',
                    fontWeight: 750,
                    letterSpacing: '0.1em',
                    lineHeight: 1.4,
                    textTransform: 'uppercase',
                  }}
                >
                  Page action
                </Typography>
                <Typography component="span" sx={{ fontSize: '1rem', fontWeight: 700 }}>
                  {pageAction.label}
                </Typography>
              </Stack>
            </ButtonBase>
          </Box>
        )}

        <Box
          component="nav"
          aria-label="Primary"
          sx={{
            position: 'relative',
            '&::before': {
              content: '""',
              position: 'absolute',
              zIndex: -1,
              insetInline: 25,
              top: -9,
              borderTop: '1px solid rgba(255, 255, 255, 0.55)',
              opacity: isOpen ? 1 : 0,
              transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms ease`,
            },
          }}
        >
          <Typography
            component="div"
            sx={{
              width: 'fit-content',
              mb: 1,
              px: 1,
              color: 'common.white',
              bgcolor: 'rgba(29, 78, 137, 0.12)',
              fontSize: '0.625rem',
              fontWeight: 750,
              letterSpacing: '0.12em',
              lineHeight: 1.4,
              textTransform: 'uppercase',
              opacity: isOpen ? 1 : 0,
              visibility: isOpen ? 'visible' : 'hidden',
              transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms ease`,
              '@media (prefers-reduced-motion: reduce)': {
                transitionDuration: '0ms',
              },
            }}
          >
            Navigation
          </Typography>
          <Stack spacing={1} sx={{ width: 220, mx: 'auto' }}>
            {navigationItems.map((item, index) => {
              const current = isCurrentPath(location.pathname, item.to)
              const Icon = item.icon
              const visualIndex = index + (pageAction ? 1 : 0)

              return (
                <ButtonBase
                  key={item.to}
                  component={Link}
                  to={item.to}
                  data-menu-entry={`navigation-${item.label.toLowerCase()}`}
                  data-enter-duration-ms={OPEN_DURATION}
                  data-exit-duration-ms={CLOSE_DURATION}
                  tabIndex={isOpen ? 0 : -1}
                  aria-current={current ? 'page' : undefined}
                  onClick={(event) => selectDestination(event, item.to)}
                  sx={{
                    ...entryMotion(isOpen, visualIndex, entryCount),
                    minHeight: 48,
                    px: 2.25,
                    borderRadius: 999,
                    justifyContent: 'flex-start',
                    gap: 1.5,
                    color: current ? 'primary.contrastText' : 'text.heading',
                    bgcolor: current ? 'primary.main' : 'background.paper',
                    boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
                    fontSize: '1rem',
                    fontWeight: current ? 700 : 650,
                    '&:hover': {
                      bgcolor: current ? 'primary.dark' : 'background.paper',
                    },
                    '&:active': { transform: 'translate3d(0, 1px, 0) scale(0.98)' },
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
        </Box>
      </Box>

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
          data-launcher-state={isOpen ? 'open' : 'closed'}
          data-icon-duration-ms={LAUNCHER_ICON_DURATION}
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
          <BrandIcon
            data-launcher-icon="brand"
            aria-hidden="true"
            sx={{
              position: 'absolute',
              fontSize: 30,
              opacity: isOpen ? 0 : 1,
              transform: isOpen ? 'rotate(-45deg) scale(0.72)' : 'rotate(0deg) scale(1)',
              transition: `opacity ${LAUNCHER_ICON_DURATION}ms ease, transform ${LAUNCHER_ICON_DURATION}ms cubic-bezier(0.16, 1, 0.3, 1)`,
              '@media (prefers-reduced-motion: reduce)': {
                transitionDuration: '0ms',
              },
            }}
          />
          <CloseIcon
            data-launcher-icon="close"
            aria-hidden="true"
            sx={{
              position: 'absolute',
              fontSize: 28,
              opacity: isOpen ? 1 : 0,
              transform: isOpen ? 'rotate(0deg) scale(1)' : 'rotate(45deg) scale(0.72)',
              transition: `opacity ${LAUNCHER_ICON_DURATION}ms ease, transform ${LAUNCHER_ICON_DURATION}ms cubic-bezier(0.16, 1, 0.3, 1)`,
              '@media (prefers-reduced-motion: reduce)': {
                transitionDuration: '0ms',
              },
            }}
          />
        </ButtonBase>
      </Box>
    </>
  )
}
