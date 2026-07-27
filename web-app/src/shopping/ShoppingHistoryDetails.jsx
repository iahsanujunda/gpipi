import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Stack,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import { CloseIcon } from '@/app/AppIcons'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

function details(item) {
  return [item.quantity, item.note].filter(Boolean).join(' · ')
}

function formatDateTime(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function DetailField({ children, label }) {
  return (
    <Stack spacing={0.5}>
      <Typography
        sx={{
          color: 'text.secondary',
          fontSize: '0.6875rem',
          fontWeight: 700,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
        }}
      >
        {label}
      </Typography>
      <Typography>{children}</Typography>
    </Stack>
  )
}

function DetailSurface({ item, mutationPending, onClose, onRestore }) {
  const completedLabel = item.status === 'BOUGHT' ? 'Bought' : 'Removed'
  const completedAt = item.status === 'BOUGHT' ? item.boughtAt : item.removedAt

  return (
    <Stack sx={{ minHeight: 0, maxHeight: 'inherit', overflow: 'hidden' }}>
      <Stack
        direction="row"
        sx={{
          alignItems: 'center',
          justifyContent: 'space-between',
          px: { xs: 2.5, sm: 3 },
          pt: 3,
          pb: 1.5,
        }}
      >
        <Typography variant="h6">{completedLabel} item</Typography>
        <IconButton aria-label="Close item details" onClick={onClose}>
          <CloseIcon />
        </IconButton>
      </Stack>
      <Box sx={{ px: { xs: 2.5, sm: 3 }, pb: 3, overflowY: 'auto' }}>
        <Stack spacing={2.25} divider={<Divider flexItem />}>
          <Stack spacing={1}>
            <Chip
              label={completedLabel}
              size="small"
              sx={{ alignSelf: 'flex-start', ...(item.status === 'REMOVED' ? { bgcolor: '#E5EFF1' } : {}) }}
            />
            <Typography variant="h6">{item.item}</Typography>
            {details(item) && <Typography>{details(item)}</Typography>}
          </Stack>
          <DetailField label="Added">
            {formatDateTime(item.addedAt)} via Slack
          </DetailField>
          <DetailField label={completedLabel}>
            {formatDateTime(completedAt)}
          </DetailField>
        </Stack>
      </Box>
      {item.status === 'REMOVED' && (
        <Stack
          spacing={1}
          sx={{
            px: { xs: 2.5, sm: 3 },
            pt: 1.5,
            pb: 'max(20px, env(safe-area-inset-bottom))',
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Button
            variant="contained"
            loading={mutationPending}
            onClick={() => onRestore(item)}
          >
            Restore to active list
          </Button>
          <Typography color="text.secondary" variant="caption" sx={{ textAlign: 'center' }}>
            The item and its mutation history are retained.
          </Typography>
        </Stack>
      )}
    </Stack>
  )
}

export default function ShoppingHistoryDetails({
  item,
  mutationPending,
  onClose,
  onExited,
  onRestore,
  open,
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const surface = (
    <DetailSurface
      item={item}
      mutationPending={mutationPending}
      onClose={onClose}
      onRestore={onRestore}
    />
  )

  if (mobile) {
    return (
      <AnimatedBottomSheet
        open={open}
        onClose={onClose}
        disableDismiss={mutationPending}
        slotProps={{
          paper: {
            role: 'dialog',
            'aria-label': `${item.status === 'BOUGHT' ? 'Bought' : 'Removed'} ${item.item}`,
            sx: { '--bottom-sheet-feature-max-height': '720px' },
          },
          transition: { onExited },
        }}
      >
        {surface}
      </AnimatedBottomSheet>
    )
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="sm"
      aria-labelledby="shopping-history-detail-title"
      slotProps={{
        backdrop: { sx: { bgcolor: 'scrim.main' } },
        transition: { onExited },
      }}
    >
      <DialogTitle id="shopping-history-detail-title" sx={{ display: 'none' }}>
        {item.status === 'BOUGHT' ? 'Bought' : 'Removed'} {item.item}
      </DialogTitle>
      <DialogContent sx={{ p: '0 !important' }}>{surface}</DialogContent>
    </Dialog>
  )
}
