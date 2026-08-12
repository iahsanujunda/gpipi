import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

function formatMoney(value) {
  return `¥${Math.abs(Number(value)).toLocaleString('ja-JP')}`
}

function signedMoney(value) {
  return `${value < 0 ? '−' : '+'}${formatMoney(value)}`
}

function previousPeriodLabel(period) {
  return period === 'WEEKLY' ? 'last week' : 'last payday period'
}

function formatDateRange(startValue, endExclusiveValue) {
  const start = new Date(`${startValue}T00:00:00Z`)
  const end = new Date(`${endExclusiveValue}T00:00:00Z`)
  end.setUTCDate(end.getUTCDate() - 1)
  const format = (date) => new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  }).format(date)
  return `${format(start)}–${format(end)}`
}

function ReviewContent({ budget, error, onApply, onClose, pending, spend }) {
  const carry = spend.carryForward
  const action = carry.amount < 0 ? 'Subtract' : 'Add'
  const newAllowance = spend.baseCap + carry.amount
  const sourceLabel = previousPeriodLabel(budget.period)

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary" variant="body2">
        {budget.name} · {formatDateRange(spend.windowStart, spend.windowEndExclusive)}
      </Typography>

      <Box sx={{ p: 2, border: 1, borderColor: 'divider', borderRadius: 2.5, bgcolor: 'background.default' }}>
        <Typography sx={{ color: 'text.secondary', fontSize: '0.6875rem', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase' }}>
          {sourceLabel} · {formatDateRange(carry.sourceWindowStart, carry.sourceWindowEndExclusive)}
        </Typography>
        <Stack direction="row" sx={{ mt: 1, alignItems: 'baseline', justifyContent: 'space-between' }}>
          <Typography color="text.secondary" variant="body2">
            {formatMoney(carry.sourceSpent)} spent of {formatMoney(carry.sourceAllowance)}
          </Typography>
          <Typography sx={{ color: carry.amount < 0 ? 'error.main' : 'primary.main', fontWeight: 750 }}>
            {signedMoney(carry.amount)}
          </Typography>
        </Stack>
      </Box>

      <Stack spacing={1}>
        <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
          <Typography color="text.secondary">Current allowance</Typography>
          <Typography fontWeight={700}>{formatMoney(spend.baseCap)}</Typography>
        </Stack>
        <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
          <Typography color="text.secondary">From {sourceLabel}</Typography>
          <Typography sx={{ color: carry.amount < 0 ? 'error.main' : 'primary.main', fontWeight: 700 }}>
            {signedMoney(carry.amount)}
          </Typography>
        </Stack>
        <Divider />
        <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between' }}>
          <Typography fontWeight={700}>New allowance</Typography>
          <Typography variant="h6">{formatMoney(newAllowance)}</Typography>
        </Stack>
      </Stack>

      {error && <Alert severity="error">{error.message}</Alert>}

      <Stack direction="row" spacing={1.5}>
        <Button disabled={pending} fullWidth onClick={onClose} variant="outlined">Back</Button>
        <Button disabled={pending} fullWidth onClick={onApply} variant="contained">
          {pending ? 'Applying…' : `${action} ${formatMoney(carry.amount)}`}
        </Button>
      </Stack>
    </Stack>
  )
}

export default function BudgetCarryForwardDialog({ budget, error, onApply, onClose, open, pending, spend }) {
  const theme = useTheme()
  const desktop = useMediaQuery(theme.breakpoints.up('sm'))
  if (!budget || !spend?.carryForward) return null
  const carry = spend.carryForward
  const action = carry.amount < 0 ? 'Subtract' : 'Add'
  const title = `${action} ${formatMoney(carry.amount)}?`

  const content = (
    <ReviewContent
      budget={budget}
      error={error}
      onApply={onApply}
      onClose={onClose}
      pending={pending}
      spend={spend}
    />
  )

  if (desktop) {
    return (
      <Dialog open={open} onClose={pending ? undefined : onClose} fullWidth maxWidth="sm">
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>{content}</DialogContent>
      </Dialog>
    )
  }

  return (
    <AnimatedBottomSheet open={open} onClose={onClose} disableDismiss={pending}>
      <Box sx={{ px: 2.5, pt: 4, pb: 'max(24px, env(safe-area-inset-bottom))', overflowY: 'auto' }}>
        <Typography variant="h6" component="h2" sx={{ mb: 0.5 }}>{title}</Typography>
        {content}
      </Box>
    </AnimatedBottomSheet>
  )
}
