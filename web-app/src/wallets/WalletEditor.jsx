import { useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogContent,
  Divider,
  IconButton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import { CloseIcon, WalletIcon } from '@/app/AppIcons'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

function normalized(form) {
  return {
    name: form.name.trim(),
    description: form.description.trim() || null,
  }
}

function validate(form) {
  const errors = {}
  if (!form.name.trim()) errors.name = 'Enter a name for this wallet.'
  return errors
}

function WalletEditorSurface({
  errors,
  form,
  mode,
  mutationPending,
  onChange,
  onClose,
  onReview,
  onSubmit,
  serverError,
  setStage,
  stage,
}) {
  const request = normalized(form)
  return (
    <Stack sx={{ flex: '1 1 auto', width: '100%', maxHeight: 'inherit', minHeight: 0, overflow: 'hidden' }}>
      <Stack
        direction="row"
        sx={{ flexShrink: 0, alignItems: 'center', justifyContent: 'space-between', px: 3, pt: 3, pb: 1.5 }}
      >
        <Typography variant="h6">
          {stage === 'review'
            ? `Review ${mode === 'create' ? 'new wallet' : 'changes'}`
            : mode === 'create' ? 'New wallet or account' : 'Edit wallet'}
        </Typography>
        <IconButton aria-label="Close wallet editor" onClick={onClose} disabled={mutationPending}>
          <CloseIcon />
        </IconButton>
      </Stack>

      <Box sx={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain', px: 3, pb: 3 }}>
        {serverError && <Alert severity="error" sx={{ mb: 2 }}>{serverError}</Alert>}
        {stage === 'form' ? (
          <Stack spacing={2}>
            <TextField
              required
              autoFocus
              label="Name"
              placeholder="e.g. Household account"
              value={form.name}
              error={Boolean(errors.name)}
              helperText={errors.name}
              onChange={(event) => onChange('name', event.target.value)}
              slotProps={{ htmlInput: { maxLength: 120 } }}
            />
            <TextField
              multiline
              minRows={3}
              label="Description"
              placeholder="What this wallet is used for"
              value={form.description}
              onChange={(event) => onChange('description', event.target.value)}
              slotProps={{ htmlInput: { maxLength: 500 } }}
            />
            {mode === 'create' && (
              <Stack
                direction="row"
                spacing={1.25}
                sx={{ alignItems: 'center', p: 1.75, borderRadius: 2.5, bgcolor: 'highlight.main' }}
              >
                <WalletIcon aria-hidden="true" sx={{ color: 'primary.main' }} />
                <Typography variant="body2" sx={{ fontWeight: 650 }}>
                  This wallet starts at ¥0
                </Typography>
              </Stack>
            )}
          </Stack>
        ) : (
          <Stack spacing={2}>
            <Typography color="text.secondary">
              Confirm these details before saving.
            </Typography>
            <Stack divider={<Divider flexItem />} spacing={2} sx={{ p: 2, borderRadius: 2.5, bgcolor: 'background.default' }}>
              <Stack spacing={0.5}>
                <Typography color="text.secondary" variant="caption">NAME</Typography>
                <Typography sx={{ fontWeight: 700 }}>{request.name}</Typography>
              </Stack>
              <Stack spacing={0.5}>
                <Typography color="text.secondary" variant="caption">DESCRIPTION</Typography>
                <Typography>{request.description || 'No description'}</Typography>
              </Stack>
              {mode === 'create' && (
                <Stack spacing={0.5}>
                  <Typography color="text.secondary" variant="caption">STARTING BALANCE</Typography>
                  <Typography sx={{ fontWeight: 700 }}>¥0 derived from recorded activity</Typography>
                </Stack>
              )}
            </Stack>
          </Stack>
        )}
      </Box>

      <Stack spacing={1} sx={{ flexShrink: 0, px: 3, pt: 1.5, pb: 'max(20px, env(safe-area-inset-bottom))', borderTop: 1, borderColor: 'divider', bgcolor: 'background.paper' }}>
        {stage === 'form' ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="contained" onClick={onReview} disabled={!form.name.trim()}>
              Review wallet
            </Button>
          </>
        ) : (
          <>
            <Button onClick={() => setStage('form')}>Back to edit</Button>
            <Button variant="contained" onClick={onSubmit} loading={mutationPending}>
              {mode === 'create' ? 'Create wallet' : 'Save changes'}
            </Button>
          </>
        )}
      </Stack>
    </Stack>
  )
}

export default function WalletEditor({
  mutation,
  onClose,
  onExited,
  onSaved,
  open,
  wallet,
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const mode = wallet ? 'edit' : 'create'
  const initial = useMemo(() => ({
    name: wallet?.name ?? '',
    description: wallet?.description ?? '',
  }), [wallet])
  const [form, setForm] = useState(initial)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [stage, setStage] = useState('form')

  function change(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: undefined }))
    setServerError('')
  }

  function review() {
    const nextErrors = validate(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length === 0) setStage('review')
  }

  async function submit() {
    try {
      const request = normalized(form)
      if (wallet) await mutation.mutateAsync({ id: wallet.id, wallet: request })
      else await mutation.mutateAsync(request)
      onSaved(request.name)
      onClose()
    } catch (error) {
      setServerError(error.message || 'The wallet could not be saved. Try again.')
    }
  }

  const surface = (
    <WalletEditorSurface
      errors={errors}
      form={form}
      mode={mode}
      mutationPending={mutation.isPending}
      onChange={change}
      onClose={onClose}
      onReview={review}
      onSubmit={submit}
      serverError={serverError}
      setStage={setStage}
      stage={stage}
    />
  )

  if (mobile) {
    return (
      <AnimatedBottomSheet
        open={open}
        onClose={onClose}
        disableDismiss={mutation.isPending}
        slotProps={{
          paper: {
            role: 'dialog',
            'aria-label': wallet ? `Edit ${wallet.name}` : 'New wallet or account',
            sx: { '--bottom-sheet-feature-max-height': 'calc(100dvh - max(24px, env(safe-area-inset-top)))' },
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
      sx={(dialogTheme) => ({
        zIndex: dialogTheme.zIndex.modal + 4,
        '& .MuiDialog-container': {
          boxSizing: 'border-box',
          pt: 'calc(59px + env(safe-area-inset-top) + 24px)',
          pb: 'calc(72px + env(safe-area-inset-bottom) + 24px)',
        },
      })}
      slotProps={{
        paper: {
          'aria-label': wallet ? `Edit ${wallet.name}` : 'New wallet or account',
          sx: {
            m: 0,
            overflow: 'hidden',
            maxHeight: 'min(760px, calc(100dvh - 179px - env(safe-area-inset-top) - env(safe-area-inset-bottom)))',
          },
        },
        backdrop: { sx: { bgcolor: 'scrim.main' } },
        transition: { onExited },
      }}
    >
      <DialogContent
        sx={{
          display: 'flex',
          flex: '1 1 auto',
          minHeight: 0,
          p: '0 !important',
          overflow: 'hidden',
        }}
      >
        {surface}
      </DialogContent>
    </Dialog>
  )
}
