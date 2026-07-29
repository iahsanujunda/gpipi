import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogContent,
  Divider,
  IconButton,
  MenuItem,
  Skeleton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { CloseIcon, SwapIcon } from '@/app/AppIcons'
import { apiFetch } from '@/api/http'
import AdaptiveDateField from '@/components/AdaptiveDateField'
import AdaptiveSelect from '@/components/AdaptiveSelect'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'
import { invalidateMovementQueries } from './queries'

const HOUSEHOLD_ZONE = 'Asia/Tokyo'

function householdToday() {
  const parts = new Intl.DateTimeFormat('en', {
    day: '2-digit',
    month: '2-digit',
    timeZone: HOUSEHOLD_ZONE,
    year: 'numeric',
  }).formatToParts(new Date())
  const part = (type) => parts.find((candidate) => candidate.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function uuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  return [...bytes].map((byte, index) => {
    const prefix = [4, 6, 8, 10].includes(index) ? '-' : ''
    return `${prefix}${byte.toString(16).padStart(2, '0')}`
  }).join('')
}

function inputFromForm(form) {
  return {
    fromAccountId: form.fromAccountId || null,
    toAccountId: form.toAccountId || null,
    amount: Number(form.amount.replaceAll(',', '')),
    occurredOn: form.occurredOn,
    note: form.note.trim() || null,
  }
}

function validate(form, today) {
  const errors = {}
  const amount = form.amount.replaceAll(',', '')
  if (!form.fromAccountId && !form.toAccountId) errors.endpoints = 'Choose at least one tracked wallet.'
  if (form.fromAccountId && form.fromAccountId === form.toAccountId) {
    errors.endpoints = 'From and To must be different.'
  }
  if (!/^[1-9]\d*$/.test(amount)) errors.amount = 'Enter a whole JPY amount greater than zero.'
  if (!form.occurredOn) errors.occurredOn = 'Choose the date this movement happened.'
  else if (form.occurredOn > today) errors.occurredOn = 'Future money movements are not allowed.'
  return errors
}

function formatMoney(value, signed = false) {
  const number = Number(value)
  const sign = signed && number > 0 ? '+' : ''
  return `${sign}¥${number.toLocaleString('ja-JP')}`
}

function endpointName(id, accounts) {
  if (!id) return 'External account'
  return accounts.find((account) => account.id === id)?.name ?? 'Unknown wallet'
}

function BalancePreview({ error, loading, preview, onRetry }) {
  if (loading) {
    return (
      <Stack role="status" aria-label="Refreshing balance preview" spacing={1} sx={{ p: 2, borderRadius: 2.5, bgcolor: 'background.default' }}>
        <Skeleton width="46%" />
        <Skeleton height={28} />
        <Skeleton width="64%" />
      </Stack>
    )
  }
  if (error) {
    return (
      <Alert
        severity="warning"
        action={<Button color="inherit" size="small" onClick={onRetry}>Retry</Button>}
      >
        Balances could not be refreshed. You can still review this movement.
      </Alert>
    )
  }
  if (!preview) return null

  return (
    <Stack spacing={1.25} sx={{ p: 2, borderRadius: 2.5, bgcolor: 'background.default' }}>
      <Typography color="text.secondary" sx={{ fontSize: '0.75rem', fontWeight: 700, letterSpacing: '0.06em' }}>
        CURRENT RECORDED BALANCE
      </Typography>
      {preview.accounts.map((account) => (
        <Stack key={account.accountId} direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography sx={{ fontWeight: 650 }}>{account.name}</Typography>
          <Typography sx={{ color: 'text.heading', fontWeight: 750, fontVariantNumeric: 'tabular-nums' }}>
            {formatMoney(account.balanceBefore)} → {formatMoney(account.balanceAfter)}
          </Typography>
        </Stack>
      ))}
      <Typography color="text.secondary" variant="caption">
        Calculated from recorded activity; this is not a reservation.
      </Typography>
    </Stack>
  )
}

function MovementReview({ accounts, form, preview }) {
  const input = inputFromForm(form)
  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">Confirm the movement before recording it.</Typography>
      <Stack divider={<Divider flexItem />} spacing={2} sx={{ p: 2, borderRadius: 2.5, bgcolor: 'background.default' }}>
        <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography color="text.secondary">From</Typography>
          <Typography sx={{ fontWeight: 700 }}>{endpointName(form.fromAccountId, accounts)}</Typography>
        </Stack>
        <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography color="text.secondary">To</Typography>
          <Typography sx={{ fontWeight: 700 }}>{endpointName(form.toAccountId, accounts)}</Typography>
        </Stack>
        <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography color="text.secondary">Amount</Typography>
          <Typography sx={{ fontWeight: 750, fontVariantNumeric: 'tabular-nums' }}>{formatMoney(input.amount)}</Typography>
        </Stack>
        <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography color="text.secondary">Date</Typography>
          <Typography sx={{ fontWeight: 700 }}>{input.occurredOn}</Typography>
        </Stack>
        {input.note && (
          <Stack spacing={0.5}>
            <Typography color="text.secondary">Note</Typography>
            <Typography>{input.note}</Typography>
          </Stack>
        )}
      </Stack>
      {preview ? (
        <BalancePreview preview={preview} />
      ) : (
        <Alert severity="info">
          Balance preview unavailable — final balances will be checked when you record this movement.
        </Alert>
      )}
    </Stack>
  )
}

export default function MoneyMovementEditor({
  accounts,
  initialToAccountId,
  mutation,
  onClose,
  onExited,
  onSaved,
  open,
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const queryClient = useQueryClient()
  const [today] = useState(() => householdToday())
  const [form, setForm] = useState({
    fromAccountId: '',
    toAccountId: initialToAccountId ?? '',
    amount: '',
    occurredOn: today,
    note: '',
  })
  const [errors, setErrors] = useState({})
  const [stage, setStage] = useState('form')
  const [preview, setPreview] = useState(null)
  const [previewError, setPreviewError] = useState(false)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewNonce, setPreviewNonce] = useState(0)
  const [serverError, setServerError] = useState('')
  const idempotencyKeyRef = useRef(null)
  const requestSequence = useRef(0)
  const input = useMemo(() => inputFromForm(form), [form])
  const currentErrors = useMemo(() => validate(form, today), [form, today])
  const valid = Object.keys(currentErrors).length === 0

  useEffect(() => {
    if (!valid || stage !== 'form') return undefined

    const sequence = ++requestSequence.current
    const controller = new AbortController()
    const timeout = window.setTimeout(async () => {
      setPreviewLoading(true)
      setPreviewError(false)
      try {
        const result = await apiFetch('/api/money-movements/preview', {
          method: 'POST',
          body: input,
          signal: controller.signal,
        })
        if (sequence === requestSequence.current) {
          setPreview(result)
          setPreviewError(false)
        }
      } catch (error) {
        if (error.name !== 'AbortError' && sequence === requestSequence.current) {
          setPreview(null)
          setPreviewError(true)
        }
      } finally {
        if (sequence === requestSequence.current) setPreviewLoading(false)
      }
    }, 350)

    return () => {
      window.clearTimeout(timeout)
      controller.abort()
    }
  }, [input, previewNonce, stage, valid])

  function change(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
    setPreview(null)
    setPreviewError(false)
    setPreviewLoading(false)
    setErrors((current) => ({ ...current, [field]: undefined, endpoints: undefined }))
    setServerError('')
  }

  function swap() {
    setForm((current) => ({
      ...current,
      fromAccountId: current.toAccountId,
      toAccountId: current.fromAccountId,
    }))
    setPreview(null)
    setPreviewError(false)
    setPreviewLoading(false)
    setErrors({})
  }

  function review() {
    setErrors(currentErrors)
    if (!valid) return
    idempotencyKeyRef.current = uuid()
    setStage('review')
  }

  function backToEdit() {
    idempotencyKeyRef.current = null
    setStage('form')
  }

  async function submit() {
    setServerError('')
    try {
      const result = await mutation.mutateAsync({
        idempotencyKey: idempotencyKeyRef.current,
        ...input,
      })
      const affectedIds = result.accounts.map((account) => account.accountId)
      invalidateMovementQueries(queryClient, affectedIds)
      onSaved(result)
      onClose()
    } catch (error) {
      setServerError(error.message || 'The money movement could not be recorded. Try again.')
    }
  }

  const surface = (
    <Stack sx={{ flex: '1 1 auto', width: '100%', maxHeight: 'inherit', minHeight: 0, overflow: 'hidden' }}>
      <Stack direction="row" sx={{ flexShrink: 0, alignItems: 'center', justifyContent: 'space-between', px: 3, pt: 3, pb: 1.5 }}>
        <Stack spacing={0.25}>
          <Typography variant="h6">{stage === 'review' ? 'Review money movement' : 'Move money'}</Typography>
          {stage === 'form' && (
            <Typography color="text.secondary" variant="caption">
              Top up, send out, or reallocate in one movement.
            </Typography>
          )}
        </Stack>
        <IconButton aria-label="Close money movement" onClick={onClose} disabled={mutation.isPending}>
          <CloseIcon />
        </IconButton>
      </Stack>

      <Box sx={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain', px: 3, pb: 3 }}>
        {serverError && <Alert severity="error" sx={{ mb: 2 }}>{serverError}</Alert>}
        {stage === 'form' ? (
          <Stack spacing={2}>
            <AdaptiveSelect
              displayEmpty
              label="From"
              value={form.fromAccountId}
              onChange={(event) => change('fromAccountId', event.target.value)}
              error={Boolean(errors.endpoints)}
            >
              <MenuItem value="">External account</MenuItem>
              {accounts.map((account) => <MenuItem key={account.id} value={account.id}>{account.name}</MenuItem>)}
            </AdaptiveSelect>

            <Box sx={{ display: 'grid', placeItems: 'center', my: -0.75 }}>
              <IconButton
                aria-label="Swap From and To"
                onClick={swap}
                sx={{
                  width: 44,
                  height: 44,
                  color: 'primary.contrastText',
                  bgcolor: 'primary.main',
                  boxShadow: '0 5px 14px rgba(29, 78, 137, 0.22)',
                  '&:hover': { bgcolor: 'primary.dark' },
                  '&:active': { transform: 'scale(0.96)' },
                }}
              >
                <SwapIcon />
              </IconButton>
            </Box>

            <AdaptiveSelect
              displayEmpty
              label="To"
              value={form.toAccountId}
              onChange={(event) => change('toAccountId', event.target.value)}
              error={Boolean(errors.endpoints)}
              helperText={errors.endpoints}
            >
              <MenuItem value="">External account</MenuItem>
              {accounts.map((account) => <MenuItem key={account.id} value={account.id}>{account.name}</MenuItem>)}
            </AdaptiveSelect>

            <TextField
              required
              label="Amount"
              value={form.amount}
              error={Boolean(errors.amount)}
              helperText={errors.amount ?? 'Whole JPY; no decimals.'}
              onChange={(event) => change('amount', event.target.value)}
              slotProps={{
                htmlInput: { inputMode: 'numeric', pattern: '[0-9,]*' },
                input: { startAdornment: <Typography sx={{ mr: 0.75 }}>¥</Typography> },
              }}
            />
            <AdaptiveDateField
              required
              label="Date"
              value={form.occurredOn}
              onChange={(event) => change('occurredOn', event.target.value)}
              error={Boolean(errors.occurredOn)}
              helperText={errors.occurredOn}
              max={today}
            />
            <TextField
              multiline
              minRows={2}
              label="Note"
              placeholder="e.g. July salary · employer"
              value={form.note}
              onChange={(event) => change('note', event.target.value)}
              slotProps={{ htmlInput: { maxLength: 500 } }}
            />
            <BalancePreview
              error={previewError}
              loading={previewLoading}
              preview={preview}
              onRetry={() => setPreviewNonce((value) => value + 1)}
            />
          </Stack>
        ) : (
          <MovementReview accounts={accounts} form={form} preview={preview} />
        )}
      </Box>

      <Stack spacing={1} sx={{ flexShrink: 0, px: 3, pt: 1.5, pb: 'max(20px, env(safe-area-inset-bottom))', borderTop: 1, borderColor: 'divider', bgcolor: 'background.paper' }}>
        {stage === 'form' ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="contained" onClick={review} disabled={!valid}>
              Review money movement
            </Button>
          </>
        ) : (
          <>
            <Button onClick={backToEdit}>Back to edit</Button>
            <Button variant="contained" onClick={submit} loading={mutation.isPending}>
              Record money movement
            </Button>
          </>
        )}
      </Stack>
    </Stack>
  )

  if (mobile) {
    return (
      <AnimatedBottomSheet
        open={open}
        onClose={onClose}
        disableDismiss={mutation.isPending}
        slotProps={{
          paper: {
            role: 'dialog',
            'aria-label': 'Move money',
            sx: { '--bottom-sheet-feature-max-height': 'calc(100dvh - max(20px, env(safe-area-inset-top)))' },
          },
          transition: { onExited },
        }}
      >
        {surface}
      </AnimatedBottomSheet>
    )
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
      sx={(dialogTheme) => ({
        zIndex: dialogTheme.zIndex.modal + 4,
        '& .MuiDialog-container': {
          boxSizing: 'border-box',
          pt: 'calc(59px + env(safe-area-inset-top) + 24px)',
          pb: 'calc(72px + env(safe-area-inset-bottom) + 24px)',
        },
      })}
      slotProps={{
        paper: {
          'aria-label': 'Move money',
          sx: {
            m: 0,
            overflow: 'hidden',
            maxHeight: 'min(820px, calc(100dvh - 179px - env(safe-area-inset-top) - env(safe-area-inset-bottom)))',
          },
        },
        backdrop: { sx: { bgcolor: 'scrim.main' } },
        transition: { onExited },
      }}
    >
      <DialogContent
        sx={{
          display: 'flex',
          flex: '1 1 auto',
          minHeight: 0,
          p: '0 !important',
          overflow: 'hidden',
        }}
      >
        {surface}
      </DialogContent>
    </Dialog>
  )
}
