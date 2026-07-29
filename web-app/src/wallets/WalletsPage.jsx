import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  Paper,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material'
import { Link } from 'react-router'
import {
  AddIcon,
  CheckIcon,
  ChevronRightIcon,
  WalletIcon,
} from '@/app/AppIcons'
import { usePageAction } from '@/app/pageActions'
import MoneyMovementEditor from './MoneyMovementEditor'
import WalletEditor from './WalletEditor'
import {
  useCreateWallet,
  useRecordMovement,
  useWallets,
} from './queries'

function formatMoney(value) {
  const number = Number(value)
  const sign = number < 0 ? '−' : ''
  return `${sign}¥${Math.abs(number).toLocaleString('ja-JP')}`
}

function WalletSkeleton() {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(320px, 100%), 1fr))', gap: 2 }}>
      {[0, 1, 2].map((item) => (
        <Paper key={item} variant="outlined" sx={{ p: 2.5 }}>
          <Stack spacing={1.5}>
            <Skeleton width="52%" height={30} />
            <Skeleton width="36%" />
            <Skeleton width="62%" height={44} />
            <Skeleton variant="rounded" height={44} />
          </Stack>
        </Paper>
      ))}
    </Box>
  )
}

function WalletCard({ onMoveMoney, wallet }) {
  return (
    <Paper
      component="article"
      variant="outlined"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: 254,
        overflow: 'hidden',
        transition: 'transform 180ms cubic-bezier(0.16, 1, 0.3, 1), border-color 180ms ease',
        '&:hover, &:focus-within': { borderColor: 'primary.light' },
        '&:hover': { transform: 'translateY(-2px)' },
        '&:hover .wallet-detail-indicator, &:focus-within .wallet-detail-indicator': {
          color: 'primary.main',
          transform: 'translateX(2px)',
        },
        '@media (prefers-reduced-motion: reduce)': { transition: 'none', '&:hover': { transform: 'none' } },
      }}
    >
      <ButtonBase
        component={Link}
        to={`/wallets/${wallet.id}`}
        aria-label={`Open ${wallet.name}`}
        sx={{
          display: 'flex',
          flex: '1 1 auto',
          alignItems: 'stretch',
          justifyContent: 'flex-start',
          p: 2.5,
          textAlign: 'left',
        }}
      >
        <Stack spacing={2} sx={{ width: '100%' }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
            <Box sx={{ display: 'grid', placeItems: 'center', width: 42, height: 42, flex: '0 0 auto', borderRadius: 2.5, color: 'primary.main', bgcolor: 'highlight.main' }}>
              <WalletIcon />
            </Box>
            <Stack spacing={0.25} sx={{ minWidth: 0 }}>
              <Typography variant="h6" component="h2">{wallet.name}</Typography>
              <Typography color="text.secondary" variant="body2">
                {wallet.assignedBudgetCount} {wallet.assignedBudgetCount === 1 ? 'budget line' : 'budget lines'}
              </Typography>
            </Stack>
            <ChevronRightIcon
              aria-hidden="true"
              className="wallet-detail-indicator"
              sx={{
                ml: 'auto',
                mt: 0.75,
                color: 'text.secondary',
                flex: '0 0 auto',
                fontSize: 22,
                transition: 'color 180ms ease, transform 180ms cubic-bezier(0.16, 1, 0.3, 1)',
                '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
              }}
            />
          </Stack>
          <Stack spacing={0.25} sx={{ mt: 'auto' }}>
            <Typography color="text.secondary" sx={{ fontSize: '0.6875rem', fontWeight: 700, letterSpacing: '0.08em' }}>
              RECORDED BALANCE
            </Typography>
            <Typography
              sx={{
                color: 'text.heading',
                fontSize: 'clamp(1.75rem, 5cqi, 2.25rem)',
                fontWeight: 780,
                fontVariantNumeric: 'tabular-nums',
                letterSpacing: '-0.035em',
              }}
            >
              {formatMoney(wallet.balance)}
            </Typography>
          </Stack>
        </Stack>
      </ButtonBase>
      <Box sx={{ px: 2.5, pb: 2.5 }}>
        <Button fullWidth variant="contained" onClick={() => onMoveMoney(wallet)}>
          Move money
        </Button>
      </Box>
    </Paper>
  )
}

export default function WalletsPage() {
  const wallets = useWallets()
  const createMutation = useCreateWallet()
  const movementMutation = useRecordMovement()
  const [editor, setEditor] = useState(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [success, setSuccess] = useState('')

  const openCreate = useCallback(() => {
    setSuccess('')
    setEditor({ type: 'wallet' })
    setEditorOpen(true)
  }, [])

  usePageAction(useMemo(() => ({
    id: 'add-wallet',
    label: 'Add wallet or account',
    icon: AddIcon,
    onSelect: openCreate,
  }), [openCreate]))

  useEffect(() => {
    if (!success) return undefined
    const timeout = window.setTimeout(() => setSuccess(''), 6000)
    return () => window.clearTimeout(timeout)
  }, [success])

  function openMovement(wallet) {
    setSuccess('')
    setEditor({ type: 'movement', wallet })
    setEditorOpen(true)
  }

  function closeEditor() {
    setEditorOpen(false)
  }

  function finishEditorClose() {
    setEditor(null)
  }

  const rows = wallets.data ?? []
  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">Wallets</Typography>

      {success && (
        <Alert icon={<CheckIcon />} severity="success" role="status" sx={{ border: 1, borderColor: 'brandAccent.main', bgcolor: 'highlight.main' }}>
          {success}
        </Alert>
      )}

      {wallets.isPending && <WalletSkeleton />}
      {wallets.isError && (
        <Alert severity="error" action={<Button color="inherit" onClick={() => wallets.refetch()}>Retry</Button>}>
          Could not load wallets.
        </Alert>
      )}

      {!wallets.isPending && !wallets.isError && rows.length === 0 && (
        <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
          <Stack spacing={2} sx={{ alignItems: 'flex-start', maxWidth: 540 }}>
            <Box sx={{ display: 'grid', placeItems: 'center', width: 48, height: 48, borderRadius: '50%', color: 'primary.main', bgcolor: 'highlight.main' }}>
              <WalletIcon />
            </Box>
            <Stack spacing={0.5}>
              <Typography variant="h6">Create the first wallet</Typography>
              <Typography color="text.secondary">
                Budget lines will point to a wallet so every recorded expense has a clear source.
              </Typography>
            </Stack>
            <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
              Add first wallet
            </Button>
          </Stack>
        </Paper>
      )}

      {!wallets.isPending && !wallets.isError && rows.length > 0 && (
        <Box
          sx={{
            containerType: 'inline-size',
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(min(320px, 100%), 1fr))',
            gap: 2,
          }}
        >
          {rows.map((wallet) => (
            <WalletCard key={wallet.id} wallet={wallet} onMoveMoney={openMovement} />
          ))}
        </Box>
      )}

      {editor?.type === 'wallet' && (
        <WalletEditor
          mutation={createMutation}
          onClose={closeEditor}
          onExited={finishEditorClose}
          onSaved={(name) => setSuccess(`${name} created`)}
          open={editorOpen}
        />
      )}
      {editor?.type === 'movement' && (
        <MoneyMovementEditor
          accounts={rows}
          initialToAccountId={editor.wallet.id}
          mutation={movementMutation}
          onClose={closeEditor}
          onExited={finishEditorClose}
          onSaved={() => setSuccess('Money movement recorded')}
          open={editorOpen}
        />
      )}
    </Stack>
  )
}
