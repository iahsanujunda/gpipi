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

const OPEN_DURATION = 320
const CLOSE_DURATION = 220
const STAGGER = 45
const LAUNCHER_ICON_DURATION = 280
const OPEN_EASING = 'cubic-bezier(0.2, 0.75, 0.2, 1)'
const CLOSE_EASING = 'cubic-bezier(0.4, 0, 1, 1)'

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
    transitionTimingFunction: isOpen ? OPEN_EASING : CLOSE_EASING,
    transitionDelay: `${isOpen ? openDelay : closeDelay}ms`,
    '@media (prefers-reduced-motion: reduce)': {
      transform: 'none',
      transitionDuration: '0ms',
      transitionDelay: '0ms',
    },
  }
}

function MenuSectionHeader({ children, id, isOpen }) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{
        alignItems: 'center',
        width: 220,
        mx: 'auto',
        mb: 1,
        opacity: isOpen ? 1 : 0,
        visibility: isOpen ? 'visible' : 'hidden',
        transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms ${isOpen ? OPEN_EASING : CLOSE_EASING}`,
        '@media (prefers-reduced-motion: reduce)': {
          transitionDuration: '0ms',
        },
      }}
    >
      <Typography
        id={id}
        component="div"
        sx={{
          flex: '0 0 auto',
          px: 1.25,
          py: 0.5,
          borderRadius: 999,
          color: 'primary.contrastText',
          bgcolor: 'text.heading',
          fontSize: '0.6875rem',
          fontWeight: 750,
          letterSpacing: '0.1em',
          lineHeight: 1.45,
          textTransform: 'uppercase',
        }}
      >
        {children}
      </Typography>
      <Box
        aria-hidden="true"
        sx={{
          flexGrow: 1,
          height: 2,
          borderRadius: 999,
          bgcolor: 'brandAccent.main',
        }}
      />
    </Stack>
  )
}

export default function AppNavigation() {
  const [isOpen, setIsOpen] = useState(false)
  const launcherRef = useRef(null)
  const location = useLocation()
  const navigate = useNavigate()
  const { navigationGuard, pageActions } = usePageActions()
  const entryCount = navigationItems.length + pageActions.length

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

  function selectPageAction(action) {
    closeNavigation({ restoreFocus: false })
    action.onSelect()
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
          bgcolor: 'scrim.main',
          opacity: isOpen ? 1 : 0,
          pointerEvents: isOpen ? 'auto' : 'none',
          transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms ${isOpen ? OPEN_EASING : CLOSE_EASING}`,
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
          maxHeight: 'calc(100dvh - 120px - env(safe-area-inset-top) - env(safe-area-inset-bottom))',
          py: 1,
          overflowY: 'auto',
          overscrollBehavior: 'contain',
          scrollbarWidth: 'none',
          transform: 'translateX(-50%)',
          zIndex: theme.zIndex.modal + 1,
          pointerEvents: isOpen ? 'auto' : 'none',
          '&::-webkit-scrollbar': { display: 'none' },
        })}
      >
        {pageActions.length > 0 && (
          <Box
            component="section"
            aria-labelledby="page-actions-label"
            sx={{ mb: 2.25 }}
          >
            <MenuSectionHeader id="page-actions-label" isOpen={isOpen}>
              Page actions
            </MenuSectionHeader>
            <Stack spacing={1} sx={{ width: 220, mx: 'auto' }}>
              {pageActions.map((action, index) => {
                const PageActionIcon = action.icon ?? AddIcon

                return (
                  <ButtonBase
                    key={action.id}
                    data-menu-entry={`page-action-${action.id}`}
                    data-enter-duration-ms={OPEN_DURATION}
                    data-exit-duration-ms={CLOSE_DURATION}
                    tabIndex={isOpen ? 0 : -1}
                    onClick={() => selectPageAction(action)}
                    sx={{
                      ...entryMotion(isOpen, index, entryCount),
                      minHeight: 50,
                      px: 2,
                      border: 2,
                      borderColor: 'brandAccent.main',
                      borderRadius: 999,
                      justifyContent: 'flex-start',
                      gap: 1.5,
                      color: 'text.heading',
                      bgcolor: 'background.paper',
                      boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
                      fontSize: '1rem',
                      fontWeight: 700,
                      '&:hover': { bgcolor: 'highlight.main' },
                      '&:active': { transform: 'translate3d(0, 1px, 0) scale(0.98)' },
                    }}
                  >
                    <PageActionIcon
                      aria-hidden="true"
                      sx={{ fontSize: 24, color: 'primary.main', flex: '0 0 auto' }}
                    />
                    <Box component="span" sx={{ flexGrow: 1, textAlign: 'left' }}>
                      {action.label}
                    </Box>
                  </ButtonBase>
                )
              })}
            </Stack>
          </Box>
        )}

        <Box component="nav" aria-label="Primary">
          <MenuSectionHeader isOpen={isOpen}>
            Navigation
          </MenuSectionHeader>
          <Stack spacing={1} sx={{ width: 220, mx: 'auto' }}>
            {navigationItems.map((item, index) => {
              const current = isCurrentPath(location.pathname, item.to)
              const Icon = item.icon
              const visualIndex = index + pageActions.length

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
                      bgcolor: current ? 'primary.dark' : 'highlight.main',
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
        data-mask-state={isOpen ? 'dimmed' : 'clear'}
        sx={(theme) => ({
          position: 'fixed',
          insetInline: 0,
          bottom: 0,
          height: 'calc(72px + env(safe-area-inset-bottom))',
          bgcolor: 'background.default',
          zIndex: theme.zIndex.modal + 2,
          '&::before': {
            content: '""',
            position: 'absolute',
            inset: 0,
            bgcolor: 'scrim.main',
            opacity: isOpen ? 1 : 0,
            pointerEvents: 'none',
            transition: `opacity ${isOpen ? OPEN_DURATION : CLOSE_DURATION}ms ${isOpen ? OPEN_EASING : CLOSE_EASING}`,
          },
          '@media (prefers-reduced-motion: reduce)': {
            '&::before': { transitionDuration: '0ms' },
          },
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
            zIndex: 1,
            insetBlockStart: 8,
            insetInlineStart: '50%',
            width: 56,
            height: 56,
            borderRadius: '50%',
            transform: 'translateX(-50%)',
            color: 'primary.contrastText',
            bgcolor: 'primary.main',
            boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
            transition: `background-color 200ms ease, transform 200ms ${OPEN_EASING}`,
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
              transition: `opacity ${LAUNCHER_ICON_DURATION}ms ease, transform ${LAUNCHER_ICON_DURATION}ms ${OPEN_EASING}`,
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
              transition: `opacity ${LAUNCHER_ICON_DURATION}ms ease, transform ${LAUNCHER_ICON_DURATION}ms ${OPEN_EASING}`,
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
