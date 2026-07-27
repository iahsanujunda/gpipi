import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import {
  CheckIcon,
  EditIcon,
  ShoppingListIcon,
} from '@/app/AppIcons'
import ShoppingItemEditor from './ShoppingItemEditor'
import ShoppingHistoryDetails from './ShoppingHistoryDetails'
import {
  useRemoveShoppingItem,
  useRestoreShoppingItem,
  useShoppingItems,
  useUpdateShoppingItem,
} from './queries'

function details(item) {
  return [item.quantity, item.note].filter(Boolean).join(' · ')
}

function formatDate(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: new Date(value).getFullYear() === new Date().getFullYear()
      ? undefined
      : 'numeric',
  }).format(new Date(value))
}

function historyDate(item) {
  return item.status === 'BOUGHT' ? item.boughtAt : item.removedAt
}

function itemStatusLabel(item) {
  return item.status === 'BOUGHT' ? 'Bought' : 'Removed'
}

function ShoppingSkeleton() {
  return (
    <Stack role="status" aria-label="Loading shopping list" spacing={1.5}>
      {[0, 1, 2].map((key) => (
        <Paper key={key} variant="outlined" sx={{ p: 2.25 }}>
          <Stack spacing={1}>
            <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <Skeleton width="38%" height={26} />
              <Skeleton variant="rounded" width={44} height={44} />
            </Stack>
            <Skeleton width="64%" />
            <Skeleton width="36%" />
          </Stack>
        </Paper>
      ))}
    </Stack>
  )
}

function SourceNotice() {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: '32px minmax(0, 1fr)',
        gap: 1.25,
        alignItems: 'center',
        p: 1.5,
        borderRadius: 2.5,
        bgcolor: 'highlight.main',
      }}
    >
      <Box
        aria-hidden="true"
        sx={{
          display: 'grid',
          placeItems: 'center',
          width: 32,
          height: 32,
          borderRadius: '50%',
          color: 'primary.main',
          bgcolor: 'background.paper',
          fontWeight: 750,
        }}
      >
        i
      </Box>
      <Box>
        <Typography sx={{ color: 'text.primary', fontSize: '0.8125rem', fontWeight: 700 }}>
          New items start in Slack
        </Typography>
        <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
          Use “list add” in your household channel.
        </Typography>
      </Box>
    </Box>
  )
}

function ActiveCard({ item, onEdit }) {
  return (
    <Paper variant="outlined" sx={{ p: 2.25 }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
        <Box
          aria-hidden="true"
          sx={{
            width: 16,
            height: 16,
            mt: 0.5,
            flex: '0 0 auto',
            border: 1,
            borderColor: 'brandAccent.main',
            borderRadius: '50%',
            bgcolor: 'highlight.main',
          }}
        />
        <Stack spacing={0.75} sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography component="h2" variant="h6" sx={{ fontSize: '1rem' }}>
            {item.item}
          </Typography>
          {details(item) && (
            <Typography variant="body2">{details(item)}</Typography>
          )}
          <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
            Added {formatDate(item.addedAt)} via Slack
          </Typography>
        </Stack>
        <IconButton
          aria-label={`Edit ${item.item}`}
          onClick={() => onEdit(item)}
          sx={{ color: 'primary.main', border: 1, borderColor: 'divider', borderRadius: 2.5 }}
        >
          <EditIcon />
        </IconButton>
      </Stack>
    </Paper>
  )
}

function HistoryCard({ item, mutationPending, onOpen, onRestore }) {
  return (
    <Paper variant="outlined" sx={{ p: 2.25 }}>
      <Stack spacing={1.25}>
        <Stack direction="row" sx={{ alignItems: 'flex-start', justifyContent: 'space-between', gap: 1.5 }}>
          <Stack spacing={0.75} sx={{ minWidth: 0 }}>
            <Chip
              label={itemStatusLabel(item)}
              size="small"
              sx={item.status === 'REMOVED' ? { bgcolor: '#E5EFF1' } : undefined}
            />
            <Typography component="h2" variant="h6" sx={{ fontSize: '1rem' }}>
              {item.item}
            </Typography>
            {details(item) && <Typography variant="body2">{details(item)}</Typography>}
            <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
              {itemStatusLabel(item)} {formatDate(historyDate(item))}
            </Typography>
          </Stack>
          {item.status === 'REMOVED' ? (
            <Stack spacing={0.75}>
              <Button variant="outlined" onClick={() => onOpen(item)}>
                View
              </Button>
              <Button
                variant="outlined"
                loading={mutationPending}
                onClick={() => onRestore(item)}
              >
                Restore
              </Button>
            </Stack>
          ) : (
            <Button variant="outlined" onClick={() => onOpen(item)}>
              View
            </Button>
          )}
        </Stack>
      </Stack>
    </Paper>
  )
}

const activeTableGrid = 'minmax(180px, 1.1fr) minmax(220px, 1.25fr) minmax(145px, .75fr) 170px'
const historyTableGrid = 'minmax(180px, 1.1fr) minmax(220px, 1.25fr) 110px minmax(145px, .75fr) 120px'

function ShoppingTable({
  items,
  mutationPending,
  onEdit,
  onOpenHistoryItem,
  onRemove,
  onRestore,
  scope,
}) {
  const active = scope === 'active'
  const grid = active ? activeTableGrid : historyTableGrid
  const headings = active
    ? ['Item', 'Details', 'Added', 'Actions']
    : ['Item', 'Details', 'Status', 'Completed', 'Actions']

  return (
    <Box
      role="table"
      aria-label={active ? 'Active shopping items' : 'Shopping history'}
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
            gridTemplateColumns: grid,
            gap: 2,
            px: 2,
            py: 1.5,
            bgcolor: 'highlight.main',
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          {headings.map((heading) => (
            <Typography key={heading} role="columnheader" sx={tableHeadingSx}>
              {heading}
            </Typography>
          ))}
        </Box>
      </Box>
      <Box role="rowgroup">
        {items.map((item) => (
          <Box
            key={item.id}
            role="row"
            sx={{
              display: 'grid',
              gridTemplateColumns: grid,
              gap: 2,
              alignItems: 'center',
              px: 2,
              py: 2,
              borderBottom: 1,
              borderColor: 'divider',
              '&:last-of-type': { borderBottom: 0 },
            }}
          >
            <Box role="cell" sx={{ minWidth: 0 }}>
              <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>
                {item.item}
              </Typography>
              <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
                Added via Slack
              </Typography>
            </Box>
            <Typography role="cell" color={details(item) ? 'text.primary' : 'text.secondary'} variant="body2">
              {details(item) || 'No quantity or note'}
            </Typography>
            {active ? (
              <>
                <Typography role="cell" color="text.secondary" variant="body2">
                  {formatDate(item.addedAt)}
                </Typography>
                <Stack role="cell" direction="row" spacing={1}>
                  <Button onClick={() => onEdit(item)}>Edit</Button>
                  <Button
                    color="error"
                    loading={mutationPending}
                    onClick={() => onRemove(item)}
                  >
                    Remove
                  </Button>
                </Stack>
              </>
            ) : (
              <>
                <Box role="cell">
                  <Chip label={itemStatusLabel(item)} size="small" />
                </Box>
                <Typography role="cell" color="text.secondary" variant="body2">
                  {formatDate(historyDate(item))}
                </Typography>
                <Box role="cell">
                  {item.status === 'REMOVED' && (
                    <Stack direction="row" spacing={0.5}>
                      <Button onClick={() => onOpenHistoryItem(item)}>View</Button>
                      <Button
                        variant="outlined"
                        loading={mutationPending}
                        onClick={() => onRestore(item)}
                      >
                        Restore
                      </Button>
                    </Stack>
                  )}
                  {item.status === 'BOUGHT' && (
                    <Button variant="outlined" onClick={() => onOpenHistoryItem(item)}>
                      View
                    </Button>
                  )}
                </Box>
              </>
            )}
          </Box>
        ))}
      </Box>
    </Box>
  )
}

const tableHeadingSx = {
  color: 'text.secondary',
  fontSize: '0.75rem',
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
}

function EmptyState({ hasHistory, onOpenHistory, scope }) {
  const active = scope === 'active'
  return (
    <Paper variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
      <Stack spacing={2} sx={{ alignItems: 'center', textAlign: 'center' }}>
        <Box
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 64,
            height: 64,
            borderRadius: '50%',
            color: 'primary.main',
            bgcolor: 'highlight.main',
          }}
        >
          <ShoppingListIcon sx={{ fontSize: 32 }} />
        </Box>
        <Stack spacing={0.5}>
          <Typography variant="h6">
            {active ? 'Nothing active right now' : 'No shopping history yet'}
          </Typography>
          <Typography color="text.secondary" variant="body2">
            {active
              ? 'Items added from Slack will appear here.'
              : 'Bought and removed items will appear here.'}
          </Typography>
        </Stack>
        {active && hasHistory && (
          <Button onClick={onOpenHistory}>Open History</Button>
        )}
      </Stack>
    </Paper>
  )
}

export default function ShoppingPage() {
  const query = useShoppingItems()
  const updateMutation = useUpdateShoppingItem()
  const removeMutation = useRemoveShoppingItem()
  const restoreMutation = useRestoreShoppingItem()
  const [scope, setScope] = useState('active')
  const [editorItem, setEditorItem] = useState(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [historyItem, setHistoryItem] = useState(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [actionError, setActionError] = useState('')

  useEffect(() => {
    if (!feedback || feedback.type === 'removed') return undefined
    const timeout = window.setTimeout(() => setFeedback(null), 6000)
    return () => window.clearTimeout(timeout)
  }, [feedback])

  const { activeItems, historyItems } = useMemo(() => {
    const rows = query.data ?? []
    return {
      activeItems: rows
        .filter((item) => item.status === 'PENDING')
        .sort((a, b) => new Date(a.addedAt) - new Date(b.addedAt)),
      historyItems: rows
        .filter((item) => item.status !== 'PENDING')
        .sort((a, b) => new Date(historyDate(b)) - new Date(historyDate(a))),
    }
  }, [query.data])

  const visibleItems = scope === 'active' ? activeItems : historyItems

  function openEditor(item) {
    setActionError('')
    setFeedback(null)
    setEditorItem(item)
    setEditorOpen(true)
  }

  function finishEditorClose() {
    setEditorItem(null)
  }

  function openHistoryItem(item) {
    setHistoryItem(item)
    setHistoryOpen(true)
  }

  function saved(result) {
    setFeedback(result)
    setActionError('')
  }

  async function restore(item, feedbackType = 'restored') {
    setActionError('')
    try {
      const restored = await restoreMutation.mutateAsync({
        id: item.id,
        currentMutationId: item.currentMutationId,
      })
      setFeedback({ type: feedbackType, item: restored })
      setScope('active')
      setHistoryOpen(false)
    } catch (error) {
      setActionError(error.message || 'The item could not be restored. Try again.')
    }
  }

  async function remove(item) {
    setActionError('')
    setFeedback(null)
    try {
      const removed = await removeMutation.mutateAsync({
        id: item.id,
        currentMutationId: item.currentMutationId,
      })
      setFeedback({ type: 'removed', item: removed })
    } catch (error) {
      setActionError(error.message || 'The item could not be removed. Try again.')
    }
  }

  return (
    <Stack spacing={3}>
      <Stack spacing={0.75}>
        <Typography variant="h4" component="h1">Shopping list</Typography>
        <Typography color="text.secondary">
          Manage items already added through Slack.
        </Typography>
      </Stack>

      <SourceNotice />

      {feedback && (
        <Alert
          icon={<CheckIcon fontSize="inherit" />}
          severity="success"
          role="status"
          action={feedback.type === 'removed' ? (
            <Button
              color="inherit"
              loading={restoreMutation.isPending}
              onClick={() => restore(feedback.item, 'undo')}
            >
              Undo
            </Button>
          ) : undefined}
          sx={{ border: 1, borderColor: 'brandAccent.main', bgcolor: 'highlight.main' }}
        >
          {feedback.type === 'removed'
            ? `${feedback.item.item} removed from the list`
            : feedback.type === 'updated'
              ? `${feedback.item.item} updated`
              : `${feedback.item.item} restored to the list`}
        </Alert>
      )}

      {actionError && (
        <Alert severity="error" onClose={() => setActionError('')}>
          {actionError}
        </Alert>
      )}

      <ToggleButtonGroup
        exclusive
        fullWidth
        value={scope}
        onChange={(_event, next) => next && setScope(next)}
        aria-label="Shopping list view"
        sx={{
          maxWidth: { md: 420 },
          '& .MuiToggleButton-root': {
            minHeight: 44,
            border: 0,
            bgcolor: '#E5EFF1',
            fontWeight: 700,
            '&.Mui-selected': {
              color: 'text.heading',
              bgcolor: 'background.paper',
              boxShadow: 'inset 0 0 0 1px #C9E2E5',
            },
          },
        }}
      >
        <ToggleButton value="active">Active {activeItems.length}</ToggleButton>
        <ToggleButton value="history">History {historyItems.length}</ToggleButton>
      </ToggleButtonGroup>

      {query.isPending && <ShoppingSkeleton />}

      {query.isError && (
        <Alert
          severity="error"
          action={<Button color="inherit" onClick={() => query.refetch()}>Retry</Button>}
        >
          Could not load the shopping list. Check the connection and try again.
        </Alert>
      )}

      {!query.isPending && !query.isError && visibleItems.length === 0 && (
        <EmptyState
          hasHistory={historyItems.length > 0}
          onOpenHistory={() => setScope('history')}
          scope={scope}
        />
      )}

      {!query.isPending && !query.isError && visibleItems.length > 0 && (
        <>
          <Stack spacing={1.5} sx={{ display: { xs: 'flex', md: 'none' } }}>
            {scope === 'active'
              ? activeItems.map((item) => (
                  <ActiveCard key={item.id} item={item} onEdit={openEditor} />
                ))
              : historyItems.map((item) => (
                  <HistoryCard
                    key={item.id}
                    item={item}
                    mutationPending={restoreMutation.isPending}
                    onOpen={openHistoryItem}
                    onRestore={restore}
                  />
                ))}
          </Stack>
          <ShoppingTable
            items={visibleItems}
            mutationPending={restoreMutation.isPending || removeMutation.isPending}
            onEdit={openEditor}
            onOpenHistoryItem={openHistoryItem}
            onRemove={remove}
            onRestore={restore}
            scope={scope}
          />
        </>
      )}

      {editorItem && (
        <ShoppingItemEditor
          item={editorItem}
          key={editorItem.id}
          onClose={() => setEditorOpen(false)}
          onExited={finishEditorClose}
          onSaved={saved}
          open={editorOpen}
          removeMutation={removeMutation}
          updateMutation={updateMutation}
        />
      )}

      {historyItem && (
        <ShoppingHistoryDetails
          item={historyItem}
          key={historyItem.id}
          mutationPending={restoreMutation.isPending}
          onClose={() => setHistoryOpen(false)}
          onExited={() => setHistoryItem(null)}
          onRestore={restore}
          open={historyOpen}
        />
      )}
    </Stack>
  )
}
