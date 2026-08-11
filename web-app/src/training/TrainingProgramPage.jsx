import { useState } from 'react'
import {
  Alert,
  Button,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowBackIcon } from '@/app/AppIcons'
import {
  useActivateTrainingProgram,
  useCreateTrainingProgram,
  useTrainingPrograms,
  useUpdateTrainingProgram,
} from './queries'

function nullable(value) {
  return value.trim() || null
}

export default function TrainingProgramPage() {
  const { programId } = useParams()
  const navigate = useNavigate()
  const create = useCreateTrainingProgram()
  const update = useUpdateTrainingProgram()
  const activate = useActivateTrainingProgram()
  const programs = useTrainingPrograms()
  const [error, setError] = useState(null)
  const [draft, setDraft] = useState(null)
  const editing = Boolean(programId)
  const existing = programs.data?.find((item) => item.id === programId)
  const program = draft ?? {
    name: existing?.name ?? '',
    startsOn: existing?.startsOn ?? '',
    note: existing?.note ?? '',
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    try {
      const input = {
        name: program.name.trim(),
        startsOn: program.startsOn || null,
        note: nullable(program.note),
      }
      if (editing) {
        await update.mutateAsync({ programId, program: input })
      } else {
        await create.mutateAsync(input)
      }
      navigate('/training')
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function activateProgram(programId) {
    setError(null)
    try {
      await activate.mutateAsync(programId)
      navigate('/training')
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  if (editing && programs.isPending) return <Typography role="status">Loading program…</Typography>
  if (editing && programs.isError) return <Alert severity="error">{programs.error.message}</Alert>
  if (editing && !existing) return <Alert severity="error">Training program not found.</Alert>

  const mutationPending = create.isPending || update.isPending

  return (
    <Stack spacing={3} sx={{ maxWidth: 760 }}>
      <Button component={Link} startIcon={<ArrowBackIcon />} sx={{ alignSelf: 'flex-start' }} to="/training">
        Training
      </Button>

      <Typography component="h1" variant="h4">{editing ? 'Edit Program' : 'Create Program'}</Typography>

      {error && <Alert severity="error">{error}</Alert>}

      <Paper component="form" onSubmit={submit} variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
        <Stack spacing={2}>
          <TextField
            autoFocus
            label="Program name"
            onChange={(event) => setDraft({ ...program, name: event.target.value })}
            required
            value={program.name}
          />
          <TextField
            label="Start date (optional)"
            onChange={(event) => setDraft({ ...program, startsOn: event.target.value })}
            slotProps={{ inputLabel: { shrink: true } }}
            type="date"
            value={program.startsOn}
          />
          <TextField
            label="Program note (optional)"
            multiline
            onChange={(event) => setDraft({ ...program, note: event.target.value })}
            rows={3}
            value={program.note}
          />
          <Button disabled={mutationPending} type="submit" variant="contained" size="large">
            {mutationPending ? (editing ? 'Saving…' : 'Creating…') : (editing ? 'Save Program' : 'Create Program')}
          </Button>
        </Stack>
      </Paper>

      {!editing && (programs.data?.length ?? 0) > 0 && (
        <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
          <Stack spacing={1.5}>
            <Typography component="h2" variant="h6">Saved programs</Typography>
            {programs.data.map((item) => (
              <Stack
                key={item.id}
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1.25}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
              >
                <Stack spacing={0.25}>
                  <Typography sx={{ fontWeight: 700 }}>{item.name}</Typography>
                  <Typography color="text.secondary" variant="body2">
                    {item.active ? 'Active program' : 'Inactive · history retained'}
                  </Typography>
                </Stack>
                {item.active ? (
                  <Typography color="primary.main" sx={{ fontSize: '0.75rem', fontWeight: 750, textTransform: 'uppercase' }}>
                    Active
                  </Typography>
                ) : (
                  <Button
                    disabled={activate.isPending}
                    onClick={() => activateProgram(item.id)}
                    variant="outlined"
                  >
                    Make active
                  </Button>
                )}
              </Stack>
            ))}
          </Stack>
        </Paper>
      )}
    </Stack>
  )
}
