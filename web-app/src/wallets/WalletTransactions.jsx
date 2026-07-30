import {
  Alert,
  Box,
  Button,
  Chip,
  Paper,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material'
import { ActivityIcon, MovementIcon } from '@/app/AppIcons'

const dateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

function formatMoney(value) {
  const number = Number(value)
  const sign = number > 0 ? '+' : number < 0 ? '−' : ''
  return `${sign}¥${Math.abs(number).toLocaleString('ja-JP')}`
}

function formatDate(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Unknown date' : dateFormatter.format(date)
}

function transactionTitle(item) {
  if (item.kind === 'MONEY_MOVEMENT') {
    return item.direction === 'INCOMING'
      ? `From ${item.counterpartyName}`
      : `To ${item.counterpartyName}`
  }
  return item.description?.trim() || item.merchant?.trim() || 'No description provided'
}

function transactionMetadata(item) {
  if (item.kind === 'MONEY_MOVEMENT') return item.note
  const description = item.description?.trim()
  return description && description !== transactionTitle(item) ? description : item.note
}

function TransactionCard({ item }) {
  const movement = item.kind === 'MONEY_MOVEMENT'
  return (
    <Paper
      component="article"
      role="row"
      variant="outlined"
      sx={{
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr) auto',
        gridTemplateAreas: '"description amount" "category amount" "date date"',
        columnGap: 2,
        rowGap: 1,
        alignItems: 'center',
        p: 2.5,
      }}
    >
      <Box role="cell" sx={{ gridArea: 'description', minWidth: 0 }}>
        <Typography
          sx={{
            color: transactionTitle(item) === 'No description provided'
              ? 'text.secondary'
              : 'text.primary',
            display: '-webkit-box',
            fontWeight: 400,
            overflow: 'hidden',
            WebkitBoxOrient: 'vertical',
            WebkitLineClamp: 2,
          }}
        >
          {transactionTitle(item)}
        </Typography>
      </Box>
      <Box role="cell" sx={{ gridArea: 'amount', alignSelf: 'start' }}>
        <Typography
          sx={{
            color: item.signedAmount > 0 ? 'primary.main' : 'text.heading',
            fontSize: '1.125rem',
            fontWeight: 700,
            fontVariantNumeric: 'tabular-nums',
            textAlign: 'right',
            whiteSpace: 'nowrap',
          }}
        >
          {formatMoney(item.signedAmount)}
        </Typography>
      </Box>
      <Box role="cell" sx={{ gridArea: 'category', minWidth: 0 }}>
        <Chip
          icon={movement ? <MovementIcon /> : undefined}
          label={movement ? 'Money movement' : item.categoryName}
          size="small"
          variant="outlined"
          sx={{ bgcolor: movement ? 'highlight.main' : 'background.default' }}
        />
      </Box>
      <Stack role="cell" spacing={0.25} sx={{ gridArea: 'date' }}>
        <Typography color="text.secondary" variant="body2">
          {formatDate(item.occurredAt)}
        </Typography>
        {transactionMetadata(item) && (
          <Typography color="text.secondary" variant="body2">
            {transactionMetadata(item)}
          </Typography>
        )}
      </Stack>
    </Paper>
  )
}

export default function WalletTransactions({ query }) {
  if (query.isPending) {
    return (
      <Stack role="status" aria-label="Loading wallet transactions" spacing={1.5}>
        {[0, 1, 2].map((item) => (
          <Paper key={item} variant="outlined" sx={{ p: 2.25 }}>
            <Stack spacing={1}>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Skeleton width="45%" />
                <Skeleton width={88} />
              </Stack>
              <Skeleton width="34%" />
              <Skeleton width="55%" />
            </Stack>
          </Paper>
        ))}
      </Stack>
    )
  }

  if (query.isError) {
    return (
      <Alert severity="error" action={<Button color="inherit" onClick={() => query.refetch()}>Retry</Button>}>
        Could not load wallet transactions.
      </Alert>
    )
  }

  const items = query.data?.pages.flatMap((page) => page.items) ?? []
  if (items.length === 0) {
    return (
      <Paper variant="outlined" sx={{ p: 3 }}>
        <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
          <Box sx={{ display: 'grid', placeItems: 'center', width: 44, height: 44, borderRadius: '50%', bgcolor: 'highlight.main', color: 'primary.main' }}>
            <ActivityIcon />
          </Box>
          <Typography variant="h6">No transactions yet</Typography>
          <Typography color="text.secondary">
            Recorded expenses and money movements affecting this wallet will appear here.
          </Typography>
        </Stack>
      </Paper>
    )
  }

  return (
    <Stack role="table" aria-label="Wallet transactions" spacing={1.5}>
      <Stack role="rowgroup" spacing={1.5}>
        {items.map((item) => <TransactionCard key={`${item.kind}-${item.id}`} item={item} />)}
      </Stack>
      {query.hasNextPage && (
        <Button
          variant="outlined"
          onClick={() => query.fetchNextPage()}
          loading={query.isFetchingNextPage}
          sx={{ alignSelf: 'center', minWidth: 160 }}
        >
          Load older transactions
        </Button>
      )}
    </Stack>
  )
}
