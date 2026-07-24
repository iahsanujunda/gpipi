import { useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { ActivityIcon } from '@/app/AppIcons'
import { useExpenses } from './queries'

const dateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

const sortOptions = [
  { value: 'newest', label: 'Newest first' },
  { value: 'oldest', label: 'Oldest first' },
  { value: 'highest', label: 'Highest amount' },
  { value: 'lowest', label: 'Lowest amount' },
]

function parsedTime(value) {
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? 0 : time
}

function localDateKey(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatDate(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Unknown date' : dateFormatter.format(date)
}

function formatMoney(value) {
  return `¥${Number(value).toLocaleString('ja-JP')}`
}

function sortExpenses(expenses, sort) {
  const sorted = [...expenses]

  return sorted.sort((left, right) => {
    if (sort === 'oldest') return parsedTime(left.spentAt) - parsedTime(right.spentAt)
    if (sort === 'highest') return right.amount - left.amount
    if (sort === 'lowest') return left.amount - right.amount
    return parsedTime(right.spentAt) - parsedTime(left.spentAt)
  })
}

function ActivitySkeleton() {
  return (
    <Stack role="status" aria-label="Loading activity" spacing={1.5}>
      {[0, 1, 2].map((item) => (
        <Paper key={item} variant="outlined" sx={{ p: 2.5 }}>
          <Stack spacing={1.25}>
            <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 2 }}>
              <Skeleton width="42%" height={26} />
              <Skeleton width={88} height={26} />
            </Stack>
            <Skeleton width="34%" height={24} />
            <Skeleton width="28%" height={21} />
          </Stack>
        </Paper>
      ))}
    </Stack>
  )
}

function ActivityEmptyState({ filtered, onClear }) {
  return (
    <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
      <Stack spacing={2} sx={{ alignItems: 'flex-start', maxWidth: 520 }}>
        <Box
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 44,
            height: 44,
            borderRadius: '50%',
            color: 'primary.main',
            bgcolor: 'highlight.main',
          }}
        >
          <ActivityIcon aria-hidden="true" />
        </Box>
        <Stack spacing={0.5}>
          <Typography variant="h6">
            {filtered ? 'No expenses match these filters' : 'No activity to show yet'}
          </Typography>
          <Typography color="text.secondary">
            {filtered
              ? 'Try a different category or widen the date range.'
              : 'Expenses confirmed through Slack will appear here.'}
          </Typography>
        </Stack>
        {filtered && (
          <Button variant="outlined" onClick={onClear}>
            Clear filters
          </Button>
        )}
      </Stack>
    </Paper>
  )
}

function ExpenseLedger({ expenses }) {
  return (
    <Box
      role="table"
      aria-label="Household expenses"
      sx={{
        border: { xs: 0, md: 1 },
        borderColor: { md: 'divider' },
        borderRadius: { md: 3 },
        bgcolor: { md: 'background.paper' },
        overflow: { md: 'hidden' },
      }}
    >
      <Box role="rowgroup" sx={{ display: { xs: 'none', md: 'block' } }}>
        <Box
          role="row"
          sx={{
            display: 'grid',
            gridTemplateColumns: 'minmax(130px, .8fr) minmax(200px, 1.5fr) minmax(180px, 1.1fr) minmax(120px, .7fr)',
            gap: 3,
            px: 3,
            py: 1.5,
            bgcolor: 'highlight.main',
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          {['Date', 'Merchant', 'Category', 'Amount'].map((label) => (
            <Typography
              key={label}
              role="columnheader"
              variant="body2"
              sx={{
                color: 'text.secondary',
                fontSize: '0.75rem',
                fontWeight: 700,
                letterSpacing: '0.04em',
                textAlign: label === 'Amount' ? 'right' : 'left',
                textTransform: 'uppercase',
              }}
            >
              {label}
            </Typography>
          ))}
        </Box>
      </Box>

      <Box
        role="rowgroup"
        sx={{
          display: 'grid',
          gap: { xs: 1.5, md: 0 },
        }}
      >
        {expenses.map((expense) => (
          <Paper
            key={expense.id}
            role="row"
            variant="outlined"
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'minmax(0, 1fr) auto',
                md: 'minmax(130px, .8fr) minmax(200px, 1.5fr) minmax(180px, 1.1fr) minmax(120px, .7fr)',
              },
              gridTemplateAreas: {
                xs: '"merchant amount" "category amount" "date date"',
                md: '"date merchant category amount"',
              },
              columnGap: { xs: 2, md: 3 },
              rowGap: { xs: 1, md: 0 },
              alignItems: 'center',
              p: { xs: 2.5, md: 0 },
              px: { md: 3 },
              py: { md: 2.25 },
              borderColor: 'divider',
              borderWidth: { xs: 1, md: 0 },
              borderBottomWidth: { md: 1 },
              borderRadius: { xs: 3, md: 0 },
              '&:last-of-type': { borderBottomWidth: { md: 0 } },
            }}
          >
            <Box
              role="cell"
              aria-label={`Date: ${formatDate(expense.spentAt)}`}
              sx={{ gridArea: 'date' }}
            >
              <Typography variant="body2" color="text.secondary">
                {formatDate(expense.spentAt)}
              </Typography>
            </Box>
            <Box
              role="cell"
              aria-label={`Merchant: ${expense.merchant?.trim() || 'Unspecified merchant'}`}
              sx={{ gridArea: 'merchant', minWidth: 0 }}
            >
              <Typography sx={{ color: 'text.heading', fontWeight: 700 }} noWrap>
                {expense.merchant?.trim() || 'Unspecified merchant'}
              </Typography>
            </Box>
            <Box
              role="cell"
              aria-label={`Category: ${expense.categoryName}`}
              sx={{ gridArea: 'category', minWidth: 0 }}
            >
              <Chip
                label={expense.categoryName}
                size="small"
                sx={{
                  maxWidth: '100%',
                  '& .MuiChip-label': {
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  },
                }}
              />
            </Box>
            <Box
              role="cell"
              aria-label={`Amount: ${formatMoney(expense.amount)}`}
              sx={{ gridArea: 'amount', alignSelf: { xs: 'start', md: 'center' } }}
            >
              <Typography
                sx={{
                  color: 'text.heading',
                  fontSize: { xs: '1.125rem', md: '1rem' },
                  fontWeight: 700,
                  textAlign: 'right',
                  whiteSpace: 'nowrap',
                }}
              >
                {formatMoney(expense.amount)}
              </Typography>
            </Box>
          </Paper>
        ))}
      </Box>
    </Box>
  )
}

export default function ActivityPage() {
  const expenses = useExpenses()
  const [category, setCategory] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState('newest')

  const rows = useMemo(() => expenses.data ?? [], [expenses.data])
  const categories = useMemo(
    () => [...new Set(rows.map((expense) => expense.categoryName))].sort((left, right) => left.localeCompare(right)),
    [rows],
  )
  const invalidRange = Boolean(from && to && from > to)
  const filtersActive = Boolean(category || from || to)

  const visibleExpenses = useMemo(() => {
    if (invalidRange) return []

    const filtered = rows.filter((expense) => {
      const date = localDateKey(expense.spentAt)
      return (
        (!category || expense.categoryName === category)
        && (!from || date >= from)
        && (!to || date <= to)
      )
    })

    return sortExpenses(filtered, sort)
  }, [category, from, invalidRange, rows, sort, to])

  function clearFilters() {
    setCategory('')
    setFrom('')
    setTo('')
  }

  return (
    <Stack spacing={3.5}>
      <Stack spacing={0.75}>
        <Typography variant="h4" component="h1">Activity</Typography>
        <Typography color="text.secondary" sx={{ maxWidth: '52ch' }}>
          Review the household expenses recorded through Slack.
        </Typography>
      </Stack>

      <Paper
        component="section"
        aria-labelledby="activity-filters-heading"
        variant="outlined"
        sx={{ p: { xs: 2, sm: 2.5 } }}
      >
        <Stack spacing={2}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={0.5}
            sx={{ justifyContent: 'space-between', alignItems: { sm: 'baseline' } }}
          >
            <Typography id="activity-filters-heading" variant="h6">
              Refine activity
            </Typography>
            {!expenses.isPending && !expenses.isError && (
              <Typography aria-live="polite" variant="body2" color="text.secondary">
                {visibleExpenses.length} {visibleExpenses.length === 1 ? 'expense' : 'expenses'}
              </Typography>
            )}
          </Stack>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' },
              gap: 1.5,
            }}
          >
            <TextField
              select
              label="Category"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              sx={{ gridColumn: { xs: '1 / -1', md: 'auto' } }}
            >
              <MenuItem value="">All categories</MenuItem>
              {categories.map((name) => <MenuItem key={name} value={name}>{name}</MenuItem>)}
            </TextField>
            <TextField
              label="From"
              type="date"
              value={from}
              error={invalidRange}
              onChange={(event) => setFrom(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              label="To"
              type="date"
              value={to}
              error={invalidRange}
              onChange={(event) => setTo(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              select
              label="Sort"
              value={sort}
              onChange={(event) => setSort(event.target.value)}
              sx={{ gridColumn: { xs: '1 / -1', md: 'auto' } }}
            >
              {sortOptions.map((option) => (
                <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
              ))}
            </TextField>
          </Box>

          {invalidRange && (
            <Typography role="alert" variant="body2" color="error">
              The From date must be on or before the To date.
            </Typography>
          )}

          {filtersActive && (invalidRange || visibleExpenses.length > 0) && (
            <Box>
              <Button variant="outlined" onClick={clearFilters}>Clear filters</Button>
            </Box>
          )}
        </Stack>
      </Paper>

      {expenses.isPending && <ActivitySkeleton />}

      {expenses.isError && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => expenses.refetch()}>Retry</Button>}
        >
          Could not load household activity.
        </Alert>
      )}

      {!expenses.isPending && !expenses.isError && !invalidRange && visibleExpenses.length === 0 && (
        <ActivityEmptyState filtered={filtersActive} onClear={clearFilters} />
      )}

      {!expenses.isPending && !expenses.isError && !invalidRange && visibleExpenses.length > 0 && (
        <ExpenseLedger expenses={visibleExpenses} />
      )}
    </Stack>
  )
}
