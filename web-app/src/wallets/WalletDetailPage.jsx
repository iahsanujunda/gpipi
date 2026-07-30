import { useEffect, useState } from 'react'
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
import { useNavigate, useParams } from 'react-router'
import { ArrowBackIcon, CheckIcon, EditIcon, WalletIcon } from '@/app/AppIcons'
import WalletEditor from './WalletEditor'
import WalletTransactions from './WalletTransactions'
import {
  useUpdateWallet,
  useWallet,
  useWalletTransactions,
} from './queries'

function formatMoney(value) {
  const number = Number(value)
  const sign = number < 0 ? '−' : ''
  return `${sign}¥${Math.abs(number).toLocaleString('ja-JP')}`
}

export default function WalletDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const detail = useWallet(id)
  const transactions = useWalletTransactions(id)
  const updateMutation = useUpdateWallet()
  const [editorOpen, setEditorOpen] = useState(false)
  const [editorMounted, setEditorMounted] = useState(false)
  const [success, setSuccess] = useState('')

  useEffect(() => {
    if (!success) return undefined
    const timeout = window.setTimeout(() => setSuccess(''), 6000)
    return () => window.clearTimeout(timeout)
  }, [success])

  if (detail.isPending) {
    return (
      <Stack spacing={3} role="status" aria-label="Loading wallet">
        <Skeleton width={180} height={42} />
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Skeleton width="38%" />
          <Skeleton width="54%" height={58} />
        </Paper>
        <Skeleton width={140} height={34} />
        <WalletTransactions query={transactions} />
      </Stack>
    )
  }

  if (detail.isError) {
    return (
      <Alert severity="error" action={<Button color="inherit" onClick={() => detail.refetch()}>Retry</Button>}>
        Could not load this wallet.
      </Alert>
    )
  }

  const { account, assignedBudgets } = detail.data
  return (
    <Stack spacing={3.5}>
      <Box>
        <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/wallets')} sx={{ ml: -1 }}>
          Wallets
        </Button>
      </Box>

      {success && (
        <Alert icon={<CheckIcon />} severity="success" role="status" sx={{ border: 1, borderColor: 'brandAccent.main', bgcolor: 'highlight.main' }}>
          {success}
        </Alert>
      )}

      <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <Stack direction="row" spacing={1.5} sx={{ minWidth: 0, alignItems: 'center' }}>
          <Box sx={{ display: 'grid', placeItems: 'center', width: 46, height: 46, flex: '0 0 auto', borderRadius: 2.75, color: 'primary.main', bgcolor: 'highlight.main' }}>
            <WalletIcon />
          </Box>
          <Stack spacing={0.25} sx={{ minWidth: 0 }}>
            <Typography variant="h4" component="h1">{account.name}</Typography>
            {account.description && <Typography color="text.secondary">{account.description}</Typography>}
          </Stack>
        </Stack>
        <IconButton
          aria-label={`Edit ${account.name}`}
          onClick={() => {
            setEditorMounted(true)
            setEditorOpen(true)
          }}
          sx={{ flex: '0 0 auto', color: 'primary.main', border: 1, borderColor: 'divider', borderRadius: 2.5 }}
        >
          <EditIcon />
        </IconButton>
      </Stack>

      <Paper
        component="section"
        aria-label="Wallet balance summary"
        variant="outlined"
        sx={{ p: { xs: 2.5, sm: 3 } }}
      >
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(0, 1.2fr) minmax(220px, .8fr)' }, gap: 3 }}>
          <Stack spacing={0.5}>
            <Typography color="text.secondary" sx={{ fontSize: '0.6875rem', fontWeight: 700, letterSpacing: '0.08em' }}>
              CURRENT RECORDED BALANCE
            </Typography>
            <Typography sx={{ color: 'text.heading', fontSize: 'clamp(2rem, 7vw, 3rem)', fontWeight: 780, fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.04em' }}>
              {formatMoney(account.balance)}
            </Typography>
            <Typography color="text.secondary" variant="body2">
              Incoming movements − outgoing movements − recorded expenses
            </Typography>
          </Stack>
          <Stack spacing={1}>
            <Typography color="text.secondary" sx={{ fontSize: '0.6875rem', fontWeight: 700, letterSpacing: '0.08em' }}>
              ASSIGNED BUDGETS
            </Typography>
            {assignedBudgets.length > 0 ? (
              <Stack direction="row" useFlexGap sx={{ flexWrap: 'wrap', gap: 1 }}>
                {assignedBudgets.map((budget) => (
                  <Chip key={budget.id} label={`${budget.name} · ${budget.period}`} variant="outlined" />
                ))}
              </Stack>
            ) : (
              <Typography color="text.secondary" variant="body2">No active budget lines use this wallet.</Typography>
            )}
          </Stack>
        </Box>
      </Paper>

      <Stack component="section" aria-labelledby="wallet-transactions-heading" spacing={1.5}>
        <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between', gap: 2 }}>
          <Typography id="wallet-transactions-heading" variant="h5" component="h2">Transactions</Typography>
          <Typography color="text.secondary" variant="body2">Newest first</Typography>
        </Stack>
        <WalletTransactions query={transactions} />
      </Stack>

      {editorMounted && (
        <WalletEditor
          wallet={account}
          mutation={updateMutation}
          onClose={() => setEditorOpen(false)}
          onExited={() => setEditorMounted(false)}
          onSaved={(name) => setSuccess(`${name} saved`)}
          open={editorOpen}
        />
      )}
    </Stack>
  )
}
