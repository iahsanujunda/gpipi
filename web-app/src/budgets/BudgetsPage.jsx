import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material'
import {
  AddIcon,
  CheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  EditIcon,
  WalletIcon,
  WarningIcon,
} from '@/app/AppIcons'
import { useNavigationGuard, usePageAction } from '@/app/pageActions'
import BudgetEditor from './BudgetEditor'
import {
  useBudgetSpend,
  useBudgets,
  useCreateBudget,
  useDeactivateBudget,
  useUpdateBudget,
} from './queries'
import { useWallets } from '@/wallets/queries'

const BUDGET_ZONE = 'Asia/Tokyo'

function formatMoney(value) {
  return `¥${Number(value).toLocaleString('ja-JP')}`
}

function currentBudgetDate() {
  const parts = new Intl.DateTimeFormat('en', {
    day: '2-digit',
    month: '2-digit',
    timeZone: BUDGET_ZONE,
    year: 'numeric',
  }).formatToParts(new Date())
  const part = (type) => parts.find((candidate) => candidate.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function dateFromIso(value) {
  return new Date(`${value}T00:00:00Z`)
}

function dateKey(date) {
  return [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0'),
  ].join('-')
}

function shiftWeek(value, amount) {
  const date = dateFromIso(value)
  date.setUTCDate(date.getUTCDate() + (amount * 7))
  return dateKey(date)
}

function monthStart(value) {
  const date = dateFromIso(value)
  date.setUTCDate(1)
  return dateKey(date)
}

function shiftMonth(value, amount) {
  const date = dateFromIso(monthStart(value))
  date.setUTCMonth(date.getUTCMonth() + amount)
  return dateKey(date)
}

function weekStart(value) {
  const date = dateFromIso(value)
  date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7))
  return dateKey(date)
}

function dateParts(date) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
    year: 'numeric',
  }).formatToParts(date)
  const part = (type) => parts.find((candidate) => candidate.type === type)?.value
  return {
    day: part('day'),
    month: part('month').toUpperCase(),
    year: part('year'),
  }
}

function formatPeriodWindow(period, budgetDate, spend) {
  const hasAuthoritativeWindow = Boolean(spend?.windowStart && spend?.windowEndExclusive)
  const selected = dateFromIso(hasAuthoritativeWindow ? spend.windowStart : budgetDate)
  if (period === 'MONTHLY') {
    const selectedParts = dateParts(selected)
    return `MONTHLY · ${selectedParts.month} ${selectedParts.year}`
  }

  const start = new Date(selected)
  if (!hasAuthoritativeWindow) {
    start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7))
  }
  const end = hasAuthoritativeWindow
    ? dateFromIso(spend.windowEndExclusive)
    : new Date(start)
  end.setUTCDate(end.getUTCDate() + (hasAuthoritativeWindow ? -1 : 6))
  const startParts = dateParts(start)
  const endParts = dateParts(end)
  const range = startParts.month === endParts.month
    ? `${startParts.day}–${endParts.day} ${endParts.month}`
    : `${startParts.day} ${startParts.month}–${endParts.day} ${endParts.month}`
  return `WEEKLY · ${range}`
}

function formatPeriodLabel(period, budgetDate, spend) {
  return formatPeriodWindow(period, budgetDate, spend).replace(`${period} · `, '')
}

function utilizationFor(spend) {
  if (!spend || spend.cap === 0) return null
  return Math.round((spend.spent / spend.cap) * 100)
}

function UtilizationBar({ name, spend }) {
  const percentage = utilizationFor(spend)
  if (percentage === null) return null

  const overCap = spend.cap > 0 && spend.remaining < 0
  const visualPercentage = Math.max(0, Math.min(percentage, 100))
  return (
    <Box
      aria-label={`${name} utilization`}
      aria-valuemax={100}
      aria-valuemin={0}
      aria-valuenow={visualPercentage}
      aria-valuetext={`${percentage}% used; ${formatMoney(spend.spent)} spent of ${formatMoney(spend.cap)}`}
      role="progressbar"
      sx={{
        height: 8,
        overflow: 'hidden',
        borderRadius: 999,
        bgcolor: overCap ? 'error.light' : 'highlight.main',
      }}
    >
      <Box
        sx={{
          width: `${visualPercentage}%`,
          height: '100%',
          borderRadius: 'inherit',
          bgcolor: overCap ? 'error.main' : 'primary.main',
          transition: 'width 280ms cubic-bezier(0.16, 1, 0.3, 1)',
          '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        }}
      />
    </Box>
  )
}

function SpendingLoading({ name, compact = false }) {
  return (
    <Stack
      aria-label={`Loading spending for ${name}`}
      role="status"
      spacing={compact ? 0.75 : 1}
      sx={{ py: compact ? 0 : 0.5 }}
    >
      <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
        <Skeleton width={compact ? '34%' : '28%'} />
        <Skeleton width={compact ? '34%' : '30%'} />
      </Stack>
      <Skeleton height={compact ? 16 : 20} />
    </Stack>
  )
}

function SpendingUnavailable({ onRetry, compact = false }) {
  return (
    <Stack
      direction={compact ? 'row' : { xs: 'column', sm: 'row' }}
      spacing={1.25}
      sx={{
        alignItems: compact ? 'center' : { xs: 'flex-start', sm: 'center' },
        justifyContent: 'space-between',
        p: compact ? 0 : 1.5,
        border: compact ? 0 : 1,
        borderColor: 'divider',
        borderRadius: 2,
        bgcolor: compact ? 'transparent' : 'background.default',
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
        <Box sx={{ color: 'error.main', display: 'flex' }}>
          <WarningIcon fontSize="small" aria-hidden="true" />
        </Box>
        <Stack spacing={0}>
          <Typography sx={{ fontSize: compact ? '0.75rem' : '0.8125rem', fontWeight: 700 }}>
            Spending unavailable
          </Typography>
          {!compact && (
            <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
              Budget details are still available.
            </Typography>
          )}
        </Stack>
      </Stack>
      <Button
        aria-label="Retry spending"
        onClick={onRetry}
        size="small"
        variant="outlined"
        sx={{ minHeight: 44, flexShrink: 0 }}
      >
        Retry
      </Button>
    </Stack>
  )
}

function MobileSpending({ budget, historical, isError, isPending, onRetry, spend }) {
  if (isPending) return <SpendingLoading name={budget.name} />
  if (isError || !spend) return <SpendingUnavailable onRetry={onRetry} />

  const overCap = spend.cap > 0 && spend.remaining < 0
  const percentage = utilizationFor(spend)
  if (percentage === null) {
    return (
      <Stack spacing={0.75}>
        <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
          <Stack spacing={0.25}>
            <Typography sx={metricLabelSx}>Spent</Typography>
            <Typography sx={metricValueSx}>{formatMoney(spend.spent)}</Typography>
          </Stack>
          <Stack spacing={0.25} sx={{ alignItems: 'flex-end' }}>
            <Typography sx={metricLabelSx}>Cap</Typography>
            <Typography sx={{ ...metricValueSx, color: 'text.secondary' }}>No cap set</Typography>
          </Stack>
        </Stack>
        <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
          Utilization bar omitted when cap is ¥0.
        </Typography>
      </Stack>
    )
  }

  return (
    <Stack spacing={0.9}>
      <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
        <Stack spacing={0.25}>
          <Typography sx={metricLabelSx}>Spent</Typography>
          <Typography sx={metricValueSx}>{formatMoney(spend.spent)}</Typography>
        </Stack>
        <Stack spacing={0.25} sx={{ alignItems: 'flex-end' }}>
          <Typography sx={{ ...metricLabelSx, color: overCap ? 'error.main' : 'text.secondary' }}>
            {historical ? 'Vs current cap' : overCap ? 'Over cap' : 'Remaining'}
          </Typography>
          <Typography sx={{ ...metricValueSx, color: overCap ? 'error.main' : 'text.heading' }}>
            {formatMoney(Math.abs(spend.remaining))}{' '}
            {historical ? (overCap ? 'over' : 'under') : (overCap ? 'over' : 'left')}
          </Typography>
        </Stack>
      </Stack>
      <UtilizationBar name={budget.name} spend={spend} />
      <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
        <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
          {historical ? 'Current cap' : 'Cap'} {formatMoney(spend.cap)}
        </Typography>
        <Typography
          sx={{
            color: overCap ? 'error.main' : 'text.secondary',
            fontSize: '0.75rem',
            fontWeight: 700,
          }}
        >
          {percentage}%
        </Typography>
      </Stack>
    </Stack>
  )
}

const metricLabelSx = {
  color: 'text.secondary',
  fontSize: '0.6875rem',
  fontWeight: 700,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
}

const metricValueSx = {
  color: 'text.heading',
  fontSize: '1.125rem',
  fontWeight: 720,
  whiteSpace: 'nowrap',
}

function BudgetSkeleton() {
  return (
    <Stack role="status" aria-label="Loading budget lines" spacing={1.5}>
      {[0, 1, 2].map((item) => (
        <Paper key={item} variant="outlined" sx={{ p: 2.5 }}>
          <Stack spacing={1.25}>
            <Skeleton width="42%" height={28} />
            <Skeleton />
            <Skeleton width="72%" />
            <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', pt: 1 }}>
              <Skeleton variant="rounded" width={82} height={26} />
              <Skeleton width={90} />
            </Stack>
          </Stack>
        </Paper>
      ))}
    </Stack>
  )
}

function EditButton({ budget, onEdit }) {
  return (
    <IconButton
      aria-label={`Edit ${budget.name}`}
      onClick={() => onEdit(budget)}
      sx={{
        color: 'primary.main',
        border: 1,
        borderColor: 'divider',
        borderRadius: 2.5,
      }}
    >
      <EditIcon />
    </IconButton>
  )
}

function BudgetCards({
  budgets,
  historical,
  highlightedId,
  onEdit,
  onRetrySpend,
  spendByCategory,
  spendError,
  spendPending,
}) {
  return (
    <Stack spacing={1.5} sx={{ display: { md: 'none' } }}>
      {budgets.map((budget) => {
        const spend = spendByCategory.get(budget.id)
        const overCap = spend?.cap > 0 && spend.remaining < 0
        return (
          <Paper
            key={budget.id}
            component="article"
            variant="outlined"
            data-budget-id={budget.id}
            sx={{
              p: 2.25,
              borderColor: overCap
                ? 'error.main'
                : highlightedId === budget.id ? 'brandAccent.main' : 'divider',
              borderInlineStartWidth: overCap || highlightedId === budget.id ? 4 : 1,
              transition: 'border-color 200ms ease',
            }}
          >
            <Stack spacing={1.5}>
              <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                <Stack spacing={0.5} sx={{ minWidth: 0, flexGrow: 1 }}>
                  <Typography variant="h6" component="h2">{budget.name}</Typography>
                  <Typography color="text.secondary" variant="body2">{budget.description}</Typography>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', color: 'text.secondary' }}>
                    <WalletIcon sx={{ fontSize: 17 }} />
                    <Typography variant="body2">{budget.accountName}</Typography>
                  </Stack>
                </Stack>
                <EditButton budget={budget} onEdit={onEdit} />
              </Stack>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                {historical && (
                  <Chip label="CURRENT CAP BASIS" size="small" />
                )}
                <Chip
                  label={budget.slackLoggable ? 'SLACK ON' : 'PLANNING ONLY'}
                  size="small"
                  variant="outlined"
                  sx={{ bgcolor: 'background.default' }}
                />
              </Stack>
              <MobileSpending
                budget={budget}
                historical={historical}
                isError={spendError}
                isPending={spendPending}
                onRetry={onRetrySpend}
                spend={spend}
              />
            </Stack>
          </Paper>
        )
      })}
    </Stack>
  )
}

function DesktopSpending({ budget, isError, isPending, onRetry, spend }) {
  if (isPending) return <SpendingLoading compact name={budget.name} />
  if (isError || !spend) return <SpendingUnavailable compact onRetry={onRetry} />
  if (spend.cap === 0) {
    return (
      <Stack spacing={0.25}>
        <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>
          {formatMoney(spend.spent)} / No cap
        </Typography>
        <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>No utilization bar</Typography>
      </Stack>
    )
  }

  const percentage = utilizationFor(spend)
  return (
    <Stack spacing={0.75}>
      <Typography sx={{ color: 'text.heading', fontSize: '0.8125rem', fontWeight: 700 }}>
        {formatMoney(spend.spent)} / {formatMoney(spend.cap)}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <UtilizationBar name={budget.name} spend={spend} />
        </Box>
        <Typography
          sx={{
            color: spend.cap > 0 && spend.remaining < 0 ? 'error.main' : 'text.secondary',
            fontSize: '0.6875rem',
            fontWeight: spend.remaining < 0 ? 700 : 500,
            minWidth: 34,
            textAlign: 'right',
          }}
        >
          {percentage}%
        </Typography>
      </Stack>
    </Stack>
  )
}

function Difference({ historical, spend }) {
  if (!spend) return <Typography color="text.secondary">—</Typography>
  if (spend.cap === 0) return <Typography color="text.secondary">No cap set</Typography>
  const overCap = spend.remaining < 0
  const qualifier = historical
    ? `${overCap ? 'over' : 'under'} current cap`
    : overCap ? 'over' : 'left'
  return (
    <Typography
      sx={{
        color: overCap ? 'error.main' : 'text.heading',
        fontWeight: 700,
        whiteSpace: 'nowrap',
      }}
    >
      {formatMoney(Math.abs(spend.remaining))} {qualifier}
    </Typography>
  )
}

const tableGrid = 'minmax(160px, 1.15fr) minmax(120px, .8fr) minmax(200px, 1.35fr) minmax(145px, .85fr) 60px 52px'

function BudgetTable({
  ariaLabel,
  budgets,
  historical,
  highlightedId,
  onEdit,
  onRetrySpend,
  spendByCategory,
  spendError,
  spendPending,
}) {
  return (
    <Box
      role="table"
      aria-label={ariaLabel}
      sx={{
        display: { xs: 'none', md: 'block' },
        overflow: 'hidden',
        border: 1,
        borderColor: 'divider',
        borderRadius: 3,
        bgcolor: 'background.paper',
      }}
    >
      <Box role="rowgroup">
        <Box
          role="row"
          sx={{
            display: 'grid',
            gridTemplateColumns: tableGrid,
            gap: 2,
            alignItems: 'center',
            px: 2,
            py: 1.5,
            bgcolor: 'highlight.main',
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          {[
            'Budget line',
            'Wallet',
            historical ? 'Spent / current cap' : 'Spent / cap',
            'Difference',
            'Slack',
            '',
          ].map((label, index) => (
            <Typography
              key={`${label}-${index}`}
              role="columnheader"
              color="text.secondary"
              sx={{
                fontSize: '0.75rem',
                fontWeight: 700,
                letterSpacing: '0.06em',
                textTransform: 'uppercase',
              }}
            >
              {label}
            </Typography>
          ))}
        </Box>
      </Box>
      <Box role="rowgroup">
        {budgets.map((budget) => {
          const spend = spendByCategory.get(budget.id)
          return (
            <Box
              key={budget.id}
              role="row"
              data-budget-id={budget.id}
              sx={{
                display: 'grid',
                gridTemplateColumns: tableGrid,
                gap: 2,
                alignItems: 'center',
                px: 2,
                py: 2,
                borderBottom: 1,
                borderInlineStart: 4,
                borderBottomColor: 'divider',
                borderInlineStartColor: highlightedId === budget.id ? 'brandAccent.main' : 'transparent',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              <Box role="cell" sx={{ minWidth: 0 }}>
                <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>{budget.name}</Typography>
                <Typography color="text.secondary" variant="body2" noWrap>{budget.description}</Typography>
              </Box>
              <Stack role="cell" direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
                <WalletIcon sx={{ color: 'primary.main', fontSize: 18, flex: '0 0 auto' }} />
                <Typography variant="body2" noWrap>{budget.accountName}</Typography>
              </Stack>
              <Box role="cell" sx={{ minWidth: 0 }}>
                <DesktopSpending
                  budget={budget}
                  isError={spendError}
                  isPending={spendPending}
                  onRetry={onRetrySpend}
                  spend={spend}
                />
              </Box>
              <Box role="cell">
                {!spendPending && !spendError && (
                  <Difference historical={historical} spend={spend} />
                )}
              </Box>
              <Typography role="cell" color="text.secondary" variant="body2">
                {budget.slackLoggable ? 'On' : 'Off'}
              </Typography>
              <Box role="cell">
                <Button onClick={() => onEdit(budget)} sx={{ minWidth: 44, px: 0.5 }}>Edit</Button>
              </Box>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

function PeriodNavigator({
  budgetDate,
  currentDate,
  onChange,
  period,
  spend,
}) {
  const weekly = period === 'WEEKLY'
  const historical = weekly
    ? weekStart(budgetDate) !== weekStart(currentDate)
    : monthStart(budgetDate) !== monthStart(currentDate)
  const periodName = weekly ? 'week' : 'month'
  const periodLabel = formatPeriodLabel(period, budgetDate, spend)

  function move(amount) {
    const next = weekly
      ? shiftWeek(budgetDate, amount)
      : shiftMonth(budgetDate, amount)
    const currentBucket = weekly ? weekStart(currentDate) : monthStart(currentDate)
    const nextBucket = weekly ? weekStart(next) : monthStart(next)
    onChange(nextBucket > currentBucket ? currentBucket : next)
  }

  return (
    <Stack
      direction="row"
      spacing={0.75}
      sx={{
        alignItems: 'center',
        flexShrink: 0,
        flexWrap: 'nowrap',
        justifyContent: 'flex-end',
      }}
    >
      <IconButton
        aria-label={`Previous ${periodName}`}
        onClick={() => move(-1)}
        sx={periodButtonSx}
      >
        <ChevronLeftIcon />
      </IconButton>
      <Stack
        aria-live="polite"
        spacing={0.1}
        sx={{
          justifyContent: 'center',
          width: { xs: 104, sm: 116 },
          minHeight: 44,
          px: 1.25,
          border: 1,
          borderColor: historical ? 'brandAccent.main' : 'divider',
          borderRadius: 2.5,
          bgcolor: historical ? 'highlight.main' : 'background.paper',
          textAlign: 'center',
        }}
      >
        <Typography sx={periodEyebrowSx}>
          {historical ? `Past ${periodName}` : `This ${periodName}`}
        </Typography>
        <Typography sx={{ color: 'text.heading', fontSize: '0.75rem', fontWeight: 700 }}>
          {periodLabel}
        </Typography>
      </Stack>
      <IconButton
        aria-label={`Next ${periodName}`}
        disabled={!historical}
        onClick={() => move(1)}
        sx={periodButtonSx}
      >
        <ChevronRightIcon />
      </IconButton>
    </Stack>
  )
}

const periodButtonSx = {
  minWidth: 44,
  minHeight: 44,
  color: 'primary.main',
  border: 1,
  borderColor: 'divider',
  borderRadius: 2.5,
  bgcolor: 'background.paper',
}

const periodEyebrowSx = {
  color: 'text.secondary',
  fontSize: '0.625rem',
  fontWeight: 700,
  letterSpacing: '0.07em',
  textTransform: 'uppercase',
}

function BudgetPeriodSection({
  budgetDate,
  budgets,
  currentDate,
  highlightedId,
  onBudgetDateChange,
  onEdit,
  period,
  spendQuery,
}) {
  const historical = period === 'WEEKLY'
    ? weekStart(budgetDate) !== weekStart(currentDate)
    : monthStart(budgetDate) !== monthStart(currentDate)
  const spendRows = spendQuery.data ?? []
  const spendByCategory = new Map(spendRows.map((row) => [row.categoryId, row]))
  const periodSpend = spendRows.find((row) => row.period === period)
  const overCapCount = budgets.filter((budget) => {
    const spend = spendByCategory.get(budget.id)
    return spend?.cap > 0 && spend.remaining < 0
  }).length
  const summary = `${budgets.length} ${budgets.length === 1 ? 'line' : 'lines'}${
    !spendQuery.isPending && !spendQuery.isError ? ` · ${overCapCount} over cap` : ''
  }`
  const title = period === 'WEEKLY' ? 'Weekly' : 'Monthly'

  return (
    <Stack
      component="section"
      aria-labelledby={`${period.toLowerCase()}-budget-heading`}
      spacing={1.5}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) auto',
          columnGap: 1.5,
          alignItems: 'center',
        }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline', minWidth: 0 }}>
          <Typography
            id={`${period.toLowerCase()}-budget-heading`}
            variant="h6"
            component="h2"
            sx={{ whiteSpace: 'nowrap' }}
          >
            {title}
          </Typography>
          <Typography
            color="text.secondary"
            sx={{ display: { xs: 'none', sm: 'block' }, fontSize: '0.75rem' }}
          >
            {summary}
          </Typography>
        </Stack>
        <PeriodNavigator
          budgetDate={budgetDate}
          currentDate={currentDate}
          onChange={onBudgetDateChange}
          period={period}
          spend={periodSpend}
        />
      </Box>

      {historical && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr) auto',
            gap: 1.5,
            alignItems: 'center',
          }}
        >
          <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
            Past spending is compared with each line&apos;s current cap.
          </Typography>
          <Button
            size="small"
            variant="outlined"
            onClick={() => onBudgetDateChange(
              period === 'WEEKLY' ? currentDate : monthStart(currentDate),
            )}
            sx={{ minHeight: 44, whiteSpace: 'nowrap' }}
          >
            This {period === 'WEEKLY' ? 'week' : 'month'}
          </Button>
        </Box>
      )}

      <BudgetCards
        budgets={budgets}
        historical={historical}
        highlightedId={highlightedId}
        onEdit={onEdit}
        onRetrySpend={() => spendQuery.refetch()}
        spendByCategory={spendByCategory}
        spendError={spendQuery.isError}
        spendPending={spendQuery.isPending}
      />
      <BudgetTable
        ariaLabel={`${title} budget lines`}
        budgets={budgets}
        historical={historical}
        highlightedId={highlightedId}
        onEdit={onEdit}
        onRetrySpend={() => spendQuery.refetch()}
        spendByCategory={spendByCategory}
        spendError={spendQuery.isError}
        spendPending={spendQuery.isPending}
      />
    </Stack>
  )
}

export default function BudgetsPage() {
  const currentDate = useMemo(() => currentBudgetDate(), [])
  const [weeklyDate, setWeeklyDate] = useState(currentDate)
  const [monthlyDate, setMonthlyDate] = useState(() => monthStart(currentDate))
  const budgets = useBudgets()
  const wallets = useWallets()
  const weeklySpend = useBudgetSpend(weeklyDate)
  const monthlySpend = useBudgetSpend(monthlyDate)
  const createMutation = useCreateBudget()
  const updateMutation = useUpdateBudget()
  const deactivateMutation = useDeactivateBudget()
  const [editor, setEditor] = useState(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editorDirty, setEditorDirty] = useState(false)
  const [discardRequested, setDiscardRequested] = useState(false)
  const [success, setSuccess] = useState(null)
  const pendingNavigationRef = useRef(null)

  const openCreate = useCallback(() => {
    setSuccess(null)
    setEditor({ mode: 'create' })
    setEditorOpen(true)
  }, [])

  const pageAction = useMemo(() => ({
    id: 'add-budget-line',
    label: 'Add budget line',
    icon: AddIcon,
    onSelect: openCreate,
  }), [openCreate])
  usePageAction(pageAction)

  const guardNavigation = useCallback((continueNavigation) => {
    pendingNavigationRef.current = continueNavigation
    setDiscardRequested(true)
  }, [])
  useNavigationGuard(editorDirty ? guardNavigation : null)

  useEffect(() => {
    if (!editorDirty) return undefined
    function preventUnload(event) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', preventUnload)
    return () => window.removeEventListener('beforeunload', preventUnload)
  }, [editorDirty])

  useEffect(() => {
    if (!success) return undefined
    const timeout = window.setTimeout(() => setSuccess(null), 6000)
    return () => window.clearTimeout(timeout)
  }, [success])

  function openEdit(budget) {
    setSuccess(null)
    setEditor({ mode: 'edit', budget })
    setEditorOpen(true)
  }

  function closeEditor() {
    setEditorOpen(false)
    setEditorDirty(false)
  }

  function finishEditorClose() {
    setEditor(null)
    const continueNavigation = pendingNavigationRef.current
    pendingNavigationRef.current = null
    continueNavigation?.()
  }

  function resolveDiscard(discarded) {
    setDiscardRequested(false)
    if (!discarded) pendingNavigationRef.current = null
  }

  function saved(result) {
    setSuccess(result)
  }

  const rows = budgets.data ?? []
  const weeklyBudgets = rows.filter((budget) => budget.period === 'WEEKLY')
  const monthlyBudgets = rows.filter((budget) => budget.period === 'MONTHLY')

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">Budgeting</Typography>

      {success && (
        <Alert
          icon={<CheckIcon fontSize="inherit" />}
          severity="success"
          role="status"
          sx={{ border: 1, borderColor: 'brandAccent.main', bgcolor: 'highlight.main' }}
        >
          {success.type === 'deactivated'
            ? `${success.name} deactivated`
            : `${success.name} ${success.type === 'created' ? 'created' : 'saved'}`}
        </Alert>
      )}

      {budgets.isPending && <BudgetSkeleton />}

      {budgets.isError && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => budgets.refetch()}>Retry</Button>}
        >
          Could not load budget lines. Check the connection and try again.
        </Alert>
      )}

      {wallets.isError && (
        <Alert
          severity="warning"
          action={<Button color="inherit" onClick={() => wallets.refetch()}>Retry</Button>}
        >
          Wallet choices are unavailable. Budget details remain visible, but create and edit cannot be saved yet.
        </Alert>
      )}

      {!budgets.isPending && !budgets.isError && rows.length === 0 && (
        <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <Box
                sx={{
                  display: 'grid',
                  placeItems: 'center',
                  width: 40,
                  height: 40,
                  borderRadius: '50%',
                  color: 'primary.main',
                  bgcolor: 'highlight.main',
                }}
              >
                <AddIcon aria-hidden="true" />
              </Box>
              <Stack spacing={0.25}>
                <Typography variant="h6">No budget lines yet</Typography>
                <Typography color="text.secondary" variant="body2">
                  Create the household&apos;s first line.
                </Typography>
              </Stack>
            </Stack>
            <Button fullWidth variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
              Add first budget line
            </Button>
          </Stack>
        </Paper>
      )}

      {!budgets.isPending && !budgets.isError && rows.length > 0 && (
        <Stack spacing={4}>
          {weeklyBudgets.length > 0 && (
            <BudgetPeriodSection
              budgetDate={weeklyDate}
              budgets={weeklyBudgets}
              currentDate={currentDate}
              highlightedId={success?.id}
              onBudgetDateChange={setWeeklyDate}
              onEdit={openEdit}
              period="WEEKLY"
              spendQuery={weeklySpend}
            />
          )}
          {monthlyBudgets.length > 0 && (
            <BudgetPeriodSection
              budgetDate={monthlyDate}
              budgets={monthlyBudgets}
              currentDate={currentDate}
              highlightedId={success?.id}
              onBudgetDateChange={setMonthlyDate}
              onEdit={openEdit}
              period="MONTHLY"
              spendQuery={monthlySpend}
            />
          )}
        </Stack>
      )}

      {editor && (
        <BudgetEditor
          accounts={wallets.data ?? []}
          budget={editor.budget}
          createMutation={createMutation}
          deactivateMutation={deactivateMutation}
          discardRequested={discardRequested}
          key={editor.budget?.id ?? 'new-budget'}
          onClose={closeEditor}
          onDirtyChange={setEditorDirty}
          onDiscardDecision={resolveDiscard}
          onExited={finishEditorClose}
          onSaved={saved}
          open={editorOpen}
          updateMutation={updateMutation}
        />
      )}
    </Stack>
  )
}
