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
import { AddIcon, CheckIcon, EditIcon } from '@/app/AppIcons'
import { useNavigationGuard, usePageAction } from '@/app/pageActions'
import BudgetEditor from './BudgetEditor'
import {
  useBudgets,
  useCreateBudget,
  useDeactivateBudget,
  useUpdateBudget,
} from './queries'

function formatMoney(value) {
  return `¥${Number(value).toLocaleString('ja-JP')}`
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

function BudgetCards({ budgets, highlightedId, onEdit }) {
  return (
    <Stack spacing={1.5} sx={{ display: { md: 'none' } }}>
      {budgets.map((budget) => (
        <Paper
          key={budget.id}
          component="article"
          variant="outlined"
          data-budget-id={budget.id}
          sx={{
            p: 2.25,
            borderColor: highlightedId === budget.id ? 'brandAccent.main' : 'divider',
            borderInlineStartWidth: highlightedId === budget.id ? 4 : 1,
            transition: 'border-color 200ms ease',
          }}
        >
          <Stack spacing={1.5}>
            <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
              <Stack spacing={0.5} sx={{ minWidth: 0, flexGrow: 1 }}>
                <Typography variant="h6" component="h2">{budget.name}</Typography>
                <Typography color="text.secondary" variant="body2">{budget.description}</Typography>
              </Stack>
              <EditButton budget={budget} onEdit={onEdit} />
            </Stack>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Chip label={budget.period} size="small" />
              <Chip
                label={budget.slackLoggable ? 'SLACK ON' : 'PLANNING ONLY'}
                size="small"
                variant="outlined"
                sx={{ bgcolor: 'background.default' }}
              />
              <Typography
                sx={{
                  ml: 'auto !important',
                  color: 'text.heading',
                  fontSize: 18,
                  fontWeight: 700,
                  whiteSpace: 'nowrap',
                }}
              >
                {formatMoney(budget.amount)}
              </Typography>
            </Stack>
          </Stack>
        </Paper>
      ))}
    </Stack>
  )
}

function BudgetTable({ budgets, highlightedId, onEdit }) {
  return (
    <Box
      role="table"
      aria-label="Active budget lines"
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
            gridTemplateColumns: 'minmax(240px, 1.5fr) 120px 140px 140px 64px',
            gap: 2,
            alignItems: 'center',
            px: 3,
            py: 1.5,
            bgcolor: 'highlight.main',
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          {['Budget line', 'Period', 'Slack', 'Cap', ''].map((label, index) => (
            <Typography
              key={`${label}-${index}`}
              role="columnheader"
              color="text.secondary"
              sx={{
                fontSize: '0.75rem',
                fontWeight: 700,
                letterSpacing: '0.06em',
                textAlign: label === 'Cap' ? 'right' : 'left',
                textTransform: 'uppercase',
              }}
            >
              {label}
            </Typography>
          ))}
        </Box>
      </Box>
      <Box role="rowgroup">
        {budgets.map((budget) => (
          <Box
            key={budget.id}
            role="row"
            data-budget-id={budget.id}
            sx={{
              display: 'grid',
              gridTemplateColumns: 'minmax(240px, 1.5fr) 120px 140px 140px 64px',
              gap: 2,
              alignItems: 'center',
              px: 3,
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
            <Typography role="cell" color="text.secondary" variant="body2">{budget.period}</Typography>
            <Typography role="cell" color="text.secondary" variant="body2">
              {budget.slackLoggable ? 'On' : 'Off'}
            </Typography>
            <Typography role="cell" sx={{ color: 'text.heading', fontWeight: 700, textAlign: 'right' }}>
              {formatMoney(budget.amount)}
            </Typography>
            <Box role="cell">
              <Button onClick={() => onEdit(budget)} sx={{ minWidth: 0 }}>Edit</Button>
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  )
}

export default function BudgetsPage() {
  const budgets = useBudgets()
  const createMutation = useCreateBudget()
  const updateMutation = useUpdateBudget()
  const deactivateMutation = useDeactivateBudget()
  const [editor, setEditor] = useState(null)
  const [editorDirty, setEditorDirty] = useState(false)
  const [discardRequested, setDiscardRequested] = useState(false)
  const [success, setSuccess] = useState(null)
  const pendingNavigationRef = useRef(null)

  const openCreate = useCallback(() => {
    setSuccess(null)
    setEditor({ mode: 'create' })
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
  }

  function closeEditor() {
    setEditor(null)
    setEditorDirty(false)
  }

  function resolveDiscard(discarded) {
    const continueNavigation = pendingNavigationRef.current
    pendingNavigationRef.current = null
    setDiscardRequested(false)
    if (!discarded || !continueNavigation) return
    window.setTimeout(continueNavigation, 230)
  }

  function saved(result) {
    setSuccess(result)
  }

  const rows = budgets.data ?? []

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
        <Stack spacing={1.5}>
          <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between' }}>
            <Typography variant="h6" component="h2">Active budget lines</Typography>
            <Typography color="text.secondary" variant="body2">{rows.length} lines</Typography>
          </Stack>
          <BudgetCards budgets={rows} highlightedId={success?.id} onEdit={openEdit} />
          <BudgetTable budgets={rows} highlightedId={success?.id} onEdit={openEdit} />
        </Stack>
      )}

      {editor && (
        <BudgetEditor
          budget={editor.budget}
          createMutation={createMutation}
          deactivateMutation={deactivateMutation}
          discardRequested={discardRequested}
          key={editor.budget?.id ?? 'new-budget'}
          onClose={closeEditor}
          onDirtyChange={setEditorDirty}
          onDiscardDecision={resolveDiscard}
          onSaved={saved}
          open
          updateMutation={updateMutation}
        />
      )}
    </Stack>
  )
}
