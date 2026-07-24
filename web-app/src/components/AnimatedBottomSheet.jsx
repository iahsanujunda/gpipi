import { useEffect, useId, useRef } from 'react'
import { SwipeableDrawer, useMediaQuery } from '@mui/material'

const BOTTOM_SHEET_MOTION = {
  enter: 380,
  exit: 220,
}

const BOTTOM_SHEET_MAX_HEIGHT = 'calc(100dvh - max(24px, env(safe-area-inset-top)))'
const BOTTOM_SHEET_FEATURE_MAX_HEIGHT = '--bottom-sheet-feature-max-height'
const ENTER_EASING = 'cubic-bezier(0.22, 1, 0.36, 1)'
const EXIT_EASING = 'cubic-bezier(0.4, 0, 1, 1)'

const HISTORY_KEY = '__gpipiBottomSheet'
const HISTORY_GUARD = 'open'
const sheetStack = []
const sheetControllers = new Map()
let guardActive = false
let removingGuard = false
let popStateInstalled = false

function pushHistoryGuard() {
  if (guardActive || removingGuard || typeof window === 'undefined') return
  window.history.pushState(
    { ...window.history.state, [HISTORY_KEY]: HISTORY_GUARD },
    '',
    window.location.href,
  )
  guardActive = true
}

function installPopStateHandler() {
  if (popStateInstalled || typeof window === 'undefined') return
  popStateInstalled = true
  window.addEventListener('popstate', (event) => {
    if (removingGuard) {
      removingGuard = false
      if (sheetStack.length > 0) pushHistoryGuard()
      return
    }

    if (event.state?.[HISTORY_KEY] === HISTORY_GUARD) {
      guardActive = true
      return
    }

    guardActive = false
    const topId = sheetStack.at(-1)
    const controller = topId ? sheetControllers.get(topId) : null
    if (!controller) return

    if (controller.isDismissDisabled()) {
      pushHistoryGuard()
      return
    }

    controller.onBack(event)
    setTimeout(() => {
      if (sheetStack.length > 0) pushHistoryGuard()
    }, 0)
  })
}

function registerSheet(id, controller) {
  installPopStateHandler()
  sheetControllers.set(id, controller)
  const previousIndex = sheetStack.indexOf(id)
  if (previousIndex >= 0) sheetStack.splice(previousIndex, 1)
  sheetStack.push(id)
  pushHistoryGuard()
}

function unregisterSheet(id) {
  sheetControllers.delete(id)
  const index = sheetStack.indexOf(id)
  if (index >= 0) sheetStack.splice(index, 1)

  if (
    sheetStack.length === 0
    && guardActive
    && window.history.state?.[HISTORY_KEY] === HISTORY_GUARD
  ) {
    guardActive = false
    removingGuard = true
    window.history.back()
  }
}

export default function AnimatedBottomSheet({
  open,
  onClose,
  onOpen,
  disableDismiss = false,
  history = true,
  slotProps = {},
  transitionDuration,
  ...drawerProps
}) {
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const reactId = useId()
  const sheetIdRef = useRef(`bottom-sheet-${reactId}`)
  const onCloseRef = useRef(onClose)
  const disableDismissRef = useRef(disableDismiss)

  useEffect(() => {
    onCloseRef.current = onClose
    disableDismissRef.current = disableDismiss
  }, [disableDismiss, onClose])

  useEffect(() => {
    if (!open || !history || typeof window === 'undefined') return undefined
    const id = sheetIdRef.current
    registerSheet(id, {
      isDismissDisabled: () => disableDismissRef.current,
      onBack: (event) => onCloseRef.current?.(event, 'historyBack'),
    })
    return () => unregisterSheet(id)
  }, [history, open])

  const requestClose = (event, reason = 'swipeDown') => {
    if (disableDismiss) return
    onClose?.(event, reason)
  }

  const paperSlot = slotProps.paper ?? {}
  const transitionSlot = slotProps.transition ?? {}
  const backdropSlot = slotProps.backdrop ?? {}
  const paperSx = Array.isArray(paperSlot.sx) ? paperSlot.sx : [paperSlot.sx]
  const resolvedTransitionDuration = reduceMotion ? 0 : (transitionDuration ?? BOTTOM_SHEET_MOTION)
  const enterDuration = typeof resolvedTransitionDuration === 'number'
    ? resolvedTransitionDuration
    : resolvedTransitionDuration.enter
  const exitDuration = typeof resolvedTransitionDuration === 'number'
    ? resolvedTransitionDuration
    : resolvedTransitionDuration.exit

  return (
    <SwipeableDrawer
      {...drawerProps}
      anchor="bottom"
      open={open}
      onOpen={onOpen ?? (() => {})}
      onClose={requestClose}
      ModalProps={{ keepMounted: false, ...drawerProps.ModalProps }}
      disableSwipeToOpen
      disableDiscovery
      hysteresis={0.35}
      minFlingVelocity={350}
      transitionDuration={resolvedTransitionDuration}
      sx={[
        (theme) => ({ zIndex: theme.zIndex.modal + 4 }),
        drawerProps.sx,
      ]}
      slotProps={{
        ...slotProps,
        backdrop: {
          ...backdropSlot,
          sx: [
            { bgcolor: 'rgba(29, 78, 137, 0.30)' },
            backdropSlot.sx,
          ],
        },
        transition: {
          ...transitionSlot,
          easing: reduceMotion
            ? undefined
            : { enter: ENTER_EASING, exit: EXIT_EASING },
        },
        paper: {
          ...paperSlot,
          'data-motion': reduceMotion ? 'reduced' : 'slide-from-bottom',
          'data-enter-duration-ms': enterDuration,
          'data-exit-duration-ms': exitDuration,
          'data-swipe-to-dismiss': 'true',
          sx: [
            {
              overflow: 'hidden',
              borderTopLeftRadius: 16,
              borderTopRightRadius: 16,
              willChange: reduceMotion ? 'auto' : 'transform',
              '&::before': {
                content: '""',
                position: 'absolute',
                zIndex: 1,
                top: 8,
                left: '50%',
                width: 36,
                height: 4,
                borderRadius: 999,
                bgcolor: 'text.disabled',
                opacity: 0.55,
                transform: 'translateX(-50%)',
                pointerEvents: 'none',
              },
            },
            ...paperSx,
            {
              maxHeight: `min(var(${BOTTOM_SHEET_FEATURE_MAX_HEIGHT}, 100dvh), ${BOTTOM_SHEET_MAX_HEIGHT})`,
            },
          ],
        },
      }}
    />
  )
}
