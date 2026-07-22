import { Alert, Box, Button, Chip, Paper, Skeleton, Stack, Typography } from '@mui/material'
import { useBudgets } from './queries'

export default function BudgetsPage() {
  const budgets = useBudgets()

  return (
    <Stack spacing={3.5}>
      <Typography variant="h4" component="h1">Budgeting</Typography>

      {budgets.isPending && (
        <Box aria-label="Loading budget lines" sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: 2.5 }}>
          {[0, 1, 2].map((item) => (
            <Paper key={item} variant="outlined" sx={{ p: 3 }}>
              <Stack spacing={1.5}>
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
        </Box>
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

      {budgets.data?.length > 0 && (
        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: 2.5 }}>
          {budgets.data.map((budget) => (
            <Paper key={budget.id} component="article" variant="outlined" sx={{ p: 3 }}>
              <Stack spacing={2.25} sx={{ height: '100%' }}>
                <Stack spacing={0.75} sx={{ flexGrow: 1 }}>
                  <Typography variant="h6" component="h2">{budget.name}</Typography>
                  <Typography color="text.secondary" variant="body2">{budget.description}</Typography>
                </Stack>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Chip label={budget.period} size="small" />
                  <Typography sx={{ color: 'text.heading', fontSize: 18, fontWeight: 700 }}>
                    ¥{Number(budget.amount).toLocaleString('ja-JP')}
                  </Typography>
                </Stack>
              </Stack>
            </Paper>
          ))}
        </Box>
      )}
    </Stack>
  )
}
