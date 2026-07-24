import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  IconButton,
  Stack,
  Switch,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import {
  CloseIcon,
  WarningIcon,
} from '@/app/AppIcons'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

const EMPTY_FORM = {
  name: '',
  description: '',
  amount: '0',
  period: 'MONTHLY',
  active: true,
  slackLoggable: true,
}

function formFromBudget(budget) {
  if (!budget) return EMPTY_FORM
  return {
    name: budget.name,
    description: budget.description,
    amount: String(budget.amount),
    period: budget.period,
    active: budget.active,
    slackLoggable: budget.slackLoggable,
  }
}

function comparableForm(form) {
  return JSON.stringify({
    ...form,
    name: form.name.trim(),
    description: form.description.trim(),
    amount: String(form.amount).replaceAll(',', ''),
  })
}

function validate(form) {
  const errors = {}
  const amount = String(form.amount).replaceAll(',', '')

  if (!form.name.trim()) errors.name = 'Enter a name for this budget line.'
  if (!form.description.trim()) errors.description = 'Describe what belongs in this budget line.'
  if (!/^\d+$/.test(amount)) errors.amount = 'Enter a whole JPY amount of zero or greater.'
  if (!['WEEKLY', 'MONTHLY'].includes(form.period)) errors.period = 'Choose a budget period.'
  return errors
}

function requestFromForm(form) {
  return {
    name: form.name.trim(),
    description: form.description.trim(),
    amount: Number(String(form.amount).replaceAll(',', '')),
    period: form.period,
    active: form.active,
    slackLoggable: form.slackLoggable,
  }
}

function formatMoney(value) {
  return `¥${Number(value).toLocaleString('ja-JP')}`
}

function FieldSummary({ label, children }) {
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
      <Box sx={{ color: 'text.primary', fontWeight: 650 }}>{children}</Box>
    </Stack>
  )
}

function EditorForm({
  errors,
  form,
  mode,
  onChange,
  onDeactivate,
}) {
  return (
    <Stack spacing={2}>
      <TextField
        required
        label="Name"
        placeholder="e.g. Pet care"
        value={form.name}
        error={Boolean(errors.name)}
        helperText={errors.name}
        onChange={(event) => onChange('name', event.target.value)}
        slotProps={{ htmlInput: { maxLength: 120 } }}
      />
      <TextField
        required
        multiline
        minRows={2}
        label="Description"
        placeholder="What belongs in this budget line?"
        value={form.description}
        error={Boolean(errors.description)}
        helperText={errors.description}
        onChange={(event) => onChange('description', event.target.value)}
        slotProps={{ htmlInput: { maxLength: 500 } }}
      />
      <TextField
        required
        label="Budget cap"
        value={form.amount}
        error={Boolean(errors.amount)}
        helperText={errors.amount ?? 'Whole JPY; no decimals.'}
        onChange={(event) => onChange('amount', event.target.value)}
        slotProps={{
          htmlInput: {
            inputMode: 'numeric',
            pattern: '[0-9,]*',
          },
          input: { startAdornment: <Typography sx={{ mr: 0.75 }}>¥</Typography> },
        }}
      />
      <Stack spacing={0.75}>
        <Typography component="label" id="budget-period-label" sx={{ color: 'text.secondary', fontSize: '0.75rem', fontWeight: 700 }}>
          Period
        </Typography>
        <ToggleButtonGroup
          exclusive
          fullWidth
          color="primary"
          aria-labelledby="budget-period-label"
          value={form.period}
          onChange={(_event, value) => value && onChange('period', value)}
          sx={{
            '& .MuiToggleButton-root': { minHeight: 48, fontWeight: 700 },
          }}
        >
          <ToggleButton value="WEEKLY">Weekly</ToggleButton>
          <ToggleButton value="MONTHLY">Monthly</ToggleButton>
        </ToggleButtonGroup>
        {errors.period && (
          <Typography role="alert" color="error" variant="body2">{errors.period}</Typography>
        )}
      </Stack>
      <Box
        sx={{
          px: 2,
          py: 1,
          borderRadius: 2,
          bgcolor: 'background.default',
        }}
      >
        <FormControlLabel
          labelPlacement="start"
          control={(
            <Switch
              checked={form.slackLoggable}
              onChange={(event) => onChange('slackLoggable', event.target.checked)}
              slotProps={{ input: { 'aria-describedby': 'slack-logging-description' } }}
            />
          )}
          label={(
            <Stack spacing={0.25} sx={{ mr: 'auto' }}>
              <Typography sx={{ fontWeight: 700 }}>Log expenses from Slack</Typography>
              <Typography id="slack-logging-description" color="text.secondary" variant="body2">
                Include this line in expense categorization.
              </Typography>
            </Stack>
          )}
          sx={{
            width: '100%',
            m: 0,
            justifyContent: 'space-between',
            gap: 2,
            '& .MuiFormControlLabel-label': { flexGrow: 1 },
          }}
        />
      </Box>
      {mode === 'edit' && (
        <Button color="error" onClick={onDeactivate}>
          Deactivate budget line
        </Button>
      )}
    </Stack>
  )
}

function Review({ budget, form, mode }) {
  const request = requestFromForm(form)

  if (mode === 'create') {
    return (
      <Stack spacing={2}>
        <Typography color="text.secondary">
          Confirm these details before creating the budget line.
        </Typography>
        <Stack
          divider={<Divider flexItem />}
          spacing={2}
          sx={{ p: 2, borderRadius: 2, bgcolor: 'background.default' }}
        >
          <FieldSummary label="Name">{request.name}</FieldSummary>
          <FieldSummary label="Cap and period">
            <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>
              {formatMoney(request.amount)} · {request.period}
            </Typography>
          </FieldSummary>
          <FieldSummary label="Slack logging">
            {request.slackLoggable ? 'On' : 'Off'}
          </FieldSummary>
          <Typography color="text.secondary" variant="body2">{request.description}</Typography>
        </Stack>
      </Stack>
    )
  }

  const changes = [
    budget.amount !== request.amount && {
      label: 'Budget cap',
      previous: formatMoney(budget.amount),
      next: formatMoney(request.amount),
    },
    budget.period !== request.period && {
      label: 'Period',
      previous: budget.period,
      next: request.period,
    },
    budget.name !== request.name && {
      label: 'Name',
      previous: budget.name,
      next: request.name,
    },
    budget.description !== request.description && {
      label: 'Description',
      previous: budget.description,
      next: request.description,
    },
    budget.slackLoggable !== request.slackLoggable && {
      label: 'Slack logging',
      previous: budget.slackLoggable ? 'On' : 'Off',
      next: request.slackLoggable ? 'On' : 'Off',
    },
  ].filter(Boolean)

  return (
    <Stack spacing={2}>
      <Typography color="text.secondary">
        Only changed fields are shown.
      </Typography>
      <Stack
        divider={<Divider flexItem />}
        spacing={2}
        sx={{ p: 2, borderRadius: 2, bgcolor: 'background.default' }}
      >
        {changes.map((change) => (
          <FieldSummary key={change.label} label={change.label}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ justifyContent: 'space-between' }}>
              <Typography color="text.secondary">{change.previous}</Typography>
              <Typography aria-hidden="true" color="text.secondary">→</Typography>
              <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>{change.next}</Typography>
            </Stack>
          </FieldSummary>
        ))}
      </Stack>
    </Stack>
  )
}

function DeactivateConfirmation({ budget }) {
  return (
    <Stack spacing={2.5}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 44,
            height: 44,
            flex: '0 0 auto',
            borderRadius: '50%',
            color: 'error.main',
            bgcolor: '#FDECEC',
          }}
        >
          <WarningIcon aria-hidden="true" />
        </Box>
        <Typography variant="h6">
          Deactivate {budget.name}?
        </Typography>
      </Stack>
      <Typography color="text.secondary">
        Past expenses stay intact. This line will stop appearing in budgets and Slack categorization.
      </Typography>
    </Stack>
  )
}

function DiscardConfirmation({ budget }) {
  return (
    <Stack spacing={2}>
      <Typography variant="h6">Discard changes?</Typography>
      <Typography color="text.secondary">
        {budget
          ? `Your edits to ${budget.name} have not been saved.`
          : 'This new budget line has not been created.'}
      </Typography>
    </Stack>
  )
}

function EditorSurface({
  budget,
  errors,
  form,
  mode,
  mutationPending,
  onChange,
  onCloseRequest,
  onDeactivate,
  onDiscard,
  onKeepEditing,
  onReview,
  onSubmit,
  serverError,
  stage,
}) {
  const title = stage === 'review'
    ? (mode === 'create' ? 'Review new budget line' : 'Review changes')
    : (mode === 'create' ? 'New budget line' : 'Edit budget line')

  return (
    <Stack sx={{ maxHeight: 'inherit', minHeight: 0 }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', px: { xs: 2.5, sm: 3 }, pt: 3, pb: 1.5 }}
      >
        {stage === 'deactivate' || stage === 'discard'
          ? <Box />
          : <Typography variant="h6">{title}</Typography>}
        <IconButton aria-label="Close budget editor" onClick={onCloseRequest} disabled={mutationPending}>
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
        {serverError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {serverError}
          </Alert>
        )}
        {stage === 'form' && (
          <EditorForm
            errors={errors}
            form={form}
            mode={mode}
            onChange={onChange}
            onDeactivate={onDeactivate}
          />
        )}
        {stage === 'review' && <Review budget={budget} form={form} mode={mode} />}
        {stage === 'deactivate' && <DeactivateConfirmation budget={budget} />}
        {stage === 'discard' && <DiscardConfirmation budget={budget} />}
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
        {stage === 'form' && (
          <>
            <Button onClick={onCloseRequest}>Cancel</Button>
            <Button variant="contained" onClick={onReview}>
              Review {mode === 'create' ? 'budget line' : 'changes'}
            </Button>
          </>
        )}
        {stage === 'review' && (
          <>
            <Button onClick={onKeepEditing}>Back to edit</Button>
            <Button variant="contained" onClick={onSubmit} loading={mutationPending}>
              {mode === 'create' ? 'Create budget line' : 'Save changes'}
            </Button>
            <Typography color="text.secondary" variant="caption" sx={{ textAlign: 'center' }}>
              {mode === 'create'
                ? 'Creates one active budget line'
                : 'Updates future planning and categorization'}
            </Typography>
          </>
        )}
        {stage === 'deactivate' && (
          <>
            <Button variant="contained" color="error" onClick={onSubmit} loading={mutationPending}>
              Deactivate budget line
            </Button>
            <Button variant="outlined" onClick={onKeepEditing}>Keep active</Button>
          </>
        )}
        {stage === 'discard' && (
          <>
            <Button variant="contained" onClick={onKeepEditing}>Keep editing</Button>
            <Button variant="outlined" color="error" onClick={onDiscard}>Discard changes</Button>
          </>
        )}
      </Stack>
    </Stack>
  )
}

export default function BudgetEditor({
  budget,
  createMutation,
  deactivateMutation,
  discardRequested = false,
  onClose,
  onDirtyChange,
  onDiscardDecision,
  onExited,
  onSaved,
  open,
  updateMutation,
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const mode = budget ? 'edit' : 'create'
  const initialForm = useMemo(() => formFromBudget(budget), [budget])
  const [form, setForm] = useState(initialForm)
  const [stage, setStage] = useState('form')
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const dirty = comparableForm(form) !== comparableForm(initialForm)
  const mutationPending = createMutation.isPending
    || updateMutation.isPending
    || deactivateMutation.isPending

  useEffect(() => {
    onDirtyChange(dirty)
  }, [dirty, onDirtyChange])

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

  function review() {
    const nextErrors = validate(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    setServerError('')
    setStage('review')
  }

  async function submit() {
    setServerError('')
    try {
      if (stage === 'deactivate') {
        await deactivateMutation.mutateAsync(budget.id)
        onDirtyChange(false)
        onSaved({ type: 'deactivated', name: budget.name })
        onClose()
        return
      }

      const request = requestFromForm(form)
      if (mode === 'create') {
        const result = await createMutation.mutateAsync(request)
        onDirtyChange(false)
        onSaved({ type: 'created', name: request.name, id: result.id })
      } else {
        await updateMutation.mutateAsync({ id: budget.id, budget: request })
        onDirtyChange(false)
        onSaved({ type: 'updated', name: request.name, id: budget.id })
      }
      onClose()
    } catch (error) {
      setServerError(error.message || 'The budget line could not be saved. Try again.')
    }
  }

  function discard() {
    onDirtyChange(false)
    onDiscardDecision(true)
    onClose()
  }

  function keepEditing() {
    setStage('form')
    onDiscardDecision(false)
  }

  const visibleStage = discardRequested && dirty ? 'discard' : stage
  const surface = (
    <EditorSurface
      budget={budget}
      errors={errors}
      form={form}
      mode={mode}
      mutationPending={mutationPending}
      onChange={change}
      onCloseRequest={requestClose}
      onDeactivate={() => setStage('deactivate')}
      onDiscard={discard}
      onKeepEditing={keepEditing}
      onReview={review}
      onSubmit={submit}
      serverError={serverError}
      stage={visibleStage}
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
            'aria-label': mode === 'create' ? 'New budget line' : `Edit ${budget.name}`,
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
      aria-labelledby="budget-editor-dialog-title"
      slotProps={{
        paper: { sx: { maxHeight: 'min(760px, calc(100dvh - 48px))' } },
        transition: { onExited },
      }}
    >
      <DialogTitle id="budget-editor-dialog-title" sx={{ display: 'none' }}>
        {mode === 'create' ? 'New budget line' : `Edit ${budget.name}`}
      </DialogTitle>
      <DialogContent sx={{ p: '0 !important', overflow: 'hidden' }}>
        {surface}
      </DialogContent>
    </Dialog>
  )
}
