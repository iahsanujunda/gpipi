import { useState } from 'react'
import {
  Alert,
  Button,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { Link, useNavigate } from 'react-router'
import { ArrowBackIcon } from '@/app/AppIcons'
import {
  useActivateTrainingProgram,
  useCreateTrainingProgram,
  useTrainingPrograms,
} from './queries'

function nullable(value) {
  return value.trim() || null
}

export default function TrainingProgramPage() {
  const navigate = useNavigate()
  const create = useCreateTrainingProgram()
  const activate = useActivateTrainingProgram()
  const programs = useTrainingPrograms()
  const [error, setError] = useState(null)
  const [program, setProgram] = useState({ name: '', startsOn: '', note: '' })

  async function submit(event) {
    event.preventDefault()
    setError(null)
    try {
      await create.mutateAsync({
        name: program.name.trim(),
        startsOn: program.startsOn || null,
        note: nullable(program.note),
      })
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

  return (
    <Stack spacing={3} sx={{ maxWidth: 760 }}>
      <Button component={Link} startIcon={<ArrowBackIcon />} sx={{ alignSelf: 'flex-start' }} to="/training">
        Training
      </Button>

      <Typography component="h1" variant="h4">Create Program</Typography>

      {error && <Alert severity="error">{error}</Alert>}

      <Paper component="form" onSubmit={submit} variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
        <Stack spacing={2}>
          <TextField
            autoFocus
            label="Program name"
            onChange={(event) => setProgram((current) => ({ ...current, name: event.target.value }))}
            required
            value={program.name}
          />
          <TextField
            label="Start date (optional)"
            onChange={(event) => setProgram((current) => ({ ...current, startsOn: event.target.value }))}
            slotProps={{ inputLabel: { shrink: true } }}
            type="date"
            value={program.startsOn}
          />
          <TextField
            label="Program note (optional)"
            multiline
            onChange={(event) => setProgram((current) => ({ ...current, note: event.target.value }))}
            rows={3}
            value={program.note}
          />
          <Button disabled={create.isPending} type="submit" variant="contained" size="large">
            {create.isPending ? 'Creating…' : 'Create Program'}
          </Button>
        </Stack>
      </Paper>

      {(programs.data?.length ?? 0) > 0 && (
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
