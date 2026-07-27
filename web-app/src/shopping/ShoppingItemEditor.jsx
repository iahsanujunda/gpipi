import { useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import { CloseIcon, RemoveIcon } from '@/app/AppIcons'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

function formFromItem(item) {
  return {
    item: item.item,
    quantity: item.quantity ?? '',
    note: item.note ?? '',
  }
}

function comparable(form) {
  return JSON.stringify({
    item: form.item.trim(),
    quantity: form.quantity.trim(),
    note: form.note.trim(),
  })
}

function validate(form) {
  const errors = {}
  if (!form.item.trim()) errors.item = 'Enter an item name.'
  if (form.item.trim().length > 200) errors.item = 'Use 200 characters or fewer.'
  if (form.quantity.trim().length > 200) errors.quantity = 'Use 200 characters or fewer.'
  if (form.note.trim().length > 500) errors.note = 'Use 500 characters or fewer.'
  return errors
}

function requestFromForm(item, form) {
  return {
    id: item.id,
    currentMutationId: item.currentMutationId,
    item: form.item.trim(),
    quantity: form.quantity.trim() || null,
    note: form.note.trim() || null,
  }
}

function EditorSurface({
  errors,
  form,
  item,
  mutationPending,
  onChange,
  onClose,
  onDiscard,
  onKeepEditing,
  onRemove,
  onSave,
  serverError,
  stage,
}) {
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
        <Typography variant="h6">
          {stage === 'discard' ? 'Discard changes?' : 'Edit item'}
        </Typography>
        <IconButton
          aria-label="Close item editor"
          disabled={mutationPending}
          onClick={onClose}
        >
          <CloseIcon />
        </IconButton>
      </Stack>

      <Box
        sx={{
          minHeight: 0,
          overflowY: 'auto',
          overscrollBehavior: 'contain',
          px: { xs: 2.5, sm: 3 },
          pb: 3,
        }}
      >
        {serverError && <Alert severity="error" sx={{ mb: 2 }}>{serverError}</Alert>}

        {stage === 'discard' ? (
          <Typography color="text.secondary">
            Your edits to {item.item} have not been saved.
          </Typography>
        ) : (
          <Stack spacing={2.25}>
            <Stack spacing={0.75}>
              <Typography
                component="label"
                htmlFor="shopping-item-name"
                sx={fieldLabelSx}
              >
                Item
              </Typography>
              <TextField
                id="shopping-item-name"
                value={form.item}
                error={Boolean(errors.item)}
                helperText={errors.item}
                onChange={(event) => onChange('item', event.target.value)}
                slotProps={{ htmlInput: { maxLength: 200 } }}
              />
            </Stack>

            <Stack spacing={0.75}>
              <Typography
                component="label"
                htmlFor="shopping-item-quantity"
                sx={fieldLabelSx}
              >
                Quantity
              </Typography>
              <TextField
                id="shopping-item-quantity"
                value={form.quantity}
                error={Boolean(errors.quantity)}
                helperText={errors.quantity ?? 'Optional; keep the wording you use at home.'}
                onChange={(event) => onChange('quantity', event.target.value)}
                slotProps={{ htmlInput: { maxLength: 200 } }}
              />
            </Stack>

            <Stack spacing={0.75}>
              <Typography
                component="label"
                htmlFor="shopping-item-note"
                sx={fieldLabelSx}
              >
                Note
              </Typography>
              <TextField
                id="shopping-item-note"
                multiline
                minRows={2}
                value={form.note}
                error={Boolean(errors.note)}
                helperText={errors.note ?? 'Optional details such as brand, size, or purpose.'}
                onChange={(event) => onChange('note', event.target.value)}
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
            </Stack>
          </Stack>
        )}
      </Box>

      <Stack
        spacing={1}
        sx={{
          px: { xs: 2.5, sm: 3 },
          pt: 1.5,
          pb: 'max(20px, env(safe-area-inset-bottom))',
          borderTop: 1,
          borderColor: 'divider',
          bgcolor: 'background.paper',
        }}
      >
        {stage === 'discard' ? (
          <>
            <Button variant="contained" onClick={onKeepEditing}>Keep editing</Button>
            <Button color="error" variant="outlined" onClick={onDiscard}>
              Discard changes
            </Button>
          </>
        ) : (
          <>
            <Button variant="contained" loading={mutationPending} onClick={onSave}>
              Save changes
            </Button>
            <Button
              color="error"
              startIcon={<RemoveIcon />}
              variant="outlined"
              loading={mutationPending}
              onClick={onRemove}
            >
              Remove from list
            </Button>
          </>
        )}
      </Stack>
    </Stack>
  )
}

const fieldLabelSx = {
  color: 'text.secondary',
  fontSize: '0.75rem',
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
}

export default function ShoppingItemEditor({
  item,
  onClose,
  onExited,
  onSaved,
  open,
  removeMutation,
  updateMutation,
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const initialForm = useMemo(() => formFromItem(item), [item])
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [stage, setStage] = useState('form')
  const dirty = comparable(form) !== comparable(initialForm)
  const mutationPending = updateMutation.isPending || removeMutation.isPending

  function change(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: undefined }))
    setServerError('')
  }

  function requestClose() {
    if (mutationPending) return
    if (dirty) {
      setStage('discard')
      return
    }
    onClose()
  }

  async function save() {
    const nextErrors = validate(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return

    setServerError('')
    try {
      const updated = await updateMutation.mutateAsync(requestFromForm(item, form))
      onSaved({ type: 'updated', item: updated })
      onClose()
    } catch (error) {
      setServerError(error.message || 'The shopping item could not be saved. Try again.')
    }
  }

  async function remove() {
    setServerError('')
    try {
      const removed = await removeMutation.mutateAsync({
        id: item.id,
        currentMutationId: item.currentMutationId,
      })
      onSaved({ type: 'removed', item: removed })
      onClose()
    } catch (error) {
      setServerError(error.message || 'The shopping item could not be removed. Try again.')
    }
  }

  const surface = (
    <EditorSurface
      errors={errors}
      form={form}
      item={item}
      mutationPending={mutationPending}
      onChange={change}
      onClose={requestClose}
      onDiscard={onClose}
      onKeepEditing={() => setStage('form')}
      onRemove={remove}
      onSave={save}
      serverError={serverError}
      stage={stage}
    />
  )

  if (mobile) {
    return (
      <AnimatedBottomSheet
        open={open}
        onClose={requestClose}
        disableDismiss={mutationPending}
        slotProps={{
          paper: {
            role: 'dialog',
            'aria-label': `Edit ${item.item}`,
            sx: {
              '--bottom-sheet-feature-max-height': 'calc(100dvh - max(24px, env(safe-area-inset-top)))',
            },
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
      onClose={requestClose}
      fullWidth
      maxWidth="sm"
      aria-labelledby="shopping-item-editor-title"
      slotProps={{
        backdrop: { sx: { bgcolor: 'scrim.main' } },
        transition: { onExited },
        paper: { sx: { overflow: 'hidden', maxHeight: 'min(760px, calc(100dvh - 96px))' } },
      }}
    >
      <DialogTitle id="shopping-item-editor-title" sx={{ display: 'none' }}>
        Edit {item.item}
      </DialogTitle>
      <DialogContent sx={{ p: '0 !important', overflow: 'hidden' }}>
        {surface}
      </DialogContent>
    </Dialog>
  )
}
