import { Alert, Button, Chip, Paper, Skeleton, Stack, Typography } from '@mui/material'
import { useBudgets } from './queries'

export default function BudgetsPage() {
  const budgets = useBudgets()

  return (
    <Stack spacing={3}>
      <Stack spacing={0.75}>
        <Typography variant="h4" component="h1">Budget lines</Typography>
        <Typography color="text.secondary">
          Review the categories that power Slack expense logging. Inline editing comes next.
        </Typography>
      </Stack>

      {budgets.isPending && (
        <Paper variant="outlined" sx={{ p: 3 }} aria-label="Loading budget lines">
          <Stack spacing={1.5}>
            <Skeleton width="35%" />
            <Skeleton />
            <Skeleton width="70%" />
          </Stack>
        </Paper>
      )}

      {budgets.isError && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => budgets.refetch()}>Retry</Button>}
        >
          Could not load budget lines. The Ktor budget endpoint may not be connected yet.
        </Alert>
      )}

      {budgets.data?.length === 0 && (
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Typography>No budget lines have been created yet.</Typography>
        </Paper>
      )}

      {budgets.data?.map((budget) => (
        <Paper key={budget.id} variant="outlined" sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ justifyContent: 'space-between' }}>
            <Stack spacing={0.75}>
              <Typography variant="h6">{budget.name}</Typography>
              <Typography color="text.secondary">{budget.description}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Chip label={budget.period} size="small" />
              <Typography fontWeight={700}>¥{Number(budget.amount).toLocaleString('ja-JP')}</Typography>
            </Stack>
          </Stack>
        </Paper>
      ))}
    </Stack>
  )
}
