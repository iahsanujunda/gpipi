import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { Link, useNavigate, useParams } from 'react-router'
import { AddIcon, ArrowBackIcon, RemoveIcon } from '@/app/AppIcons'
import {
  useCreateTrainingWorkout,
  useTrainingExercises,
  useTrainingOverview,
} from './queries'

const emptyPrescription = () => ({
  exerciseChoice: '',
  exerciseId: null,
  createExercise: false,
  exerciseName: '',
  demoUrl: '',
  executionType: '',
  sets: '',
  rest: '',
  reps: '',
  load: '',
  rir: '',
  tempo: '',
  note: '',
})

const emptyGroup = (position = 0) => ({
  label: String.fromCharCode(65 + position),
  kind: 'STRAIGHT_SET',
  prescriptions: [emptyPrescription()],
})

function replaceAt(items, index, value) {
  return items.map((item, itemIndex) => itemIndex === index ? value : item)
}

function nullable(value) {
  return value.trim() || null
}

function toRequest(workout) {
  return {
    name: workout.name.trim(),
    note: nullable(workout.note),
    groups: workout.groups.map((group) => ({
      label: group.label.trim(),
      kind: group.kind,
      prescriptions: group.prescriptions.map((prescription) => ({
        exerciseName: prescription.exerciseName.trim(),
        exerciseId: prescription.exerciseId,
        createExercise: prescription.createExercise,
        demoUrl: nullable(prescription.demoUrl),
        executionType: prescription.executionType,
        sets: nullable(prescription.sets),
        rest: nullable(prescription.rest),
        reps: nullable(prescription.reps),
        load: nullable(prescription.load),
        rir: nullable(prescription.rir),
        tempo: nullable(prescription.tempo),
        note: nullable(prescription.note),
      })),
    })),
  }
}

export default function TrainingWorkoutAuthoringPage() {
  const { weekNumber: routeWeek } = useParams()
  const weekNumber = Number.parseInt(routeWeek, 10)
  const navigate = useNavigate()
  const overview = useTrainingOverview()
  const exercises = useTrainingExercises()
  const create = useCreateTrainingWorkout()
  const [error, setError] = useState(null)
  const [workout, setWorkout] = useState({ name: '', note: '', groups: [emptyGroup()] })

  function updateGroup(groupIndex, transform) {
    setWorkout((current) => ({
      ...current,
      groups: replaceAt(current.groups, groupIndex, transform(current.groups[groupIndex])),
    }))
  }

  function updatePrescription(groupIndex, prescriptionIndex, field, value) {
    updateGroup(groupIndex, (group) => ({
      ...group,
      prescriptions: replaceAt(
        group.prescriptions,
        prescriptionIndex,
        { ...group.prescriptions[prescriptionIndex], [field]: value },
      ),
    }))
  }

  function selectExercise(groupIndex, prescriptionIndex, choice) {
    const existingId = choice.startsWith('existing:') ? choice.slice('existing:'.length) : null
    const existing = exercises.data?.find((exercise) => exercise.id === existingId)
    updateGroup(groupIndex, (group) => ({
      ...group,
      prescriptions: replaceAt(group.prescriptions, prescriptionIndex, {
        ...group.prescriptions[prescriptionIndex],
        exerciseChoice: choice,
        exerciseId: existing?.id ?? null,
        createExercise: choice === 'new',
        exerciseName: existing?.name ?? '',
        demoUrl: existing?.demoUrl ?? '',
      }),
    }))
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    try {
      await create.mutateAsync({
        programId: overview.data.program.id,
        weekNumber,
        workout: toRequest(workout),
      })
      navigate(`/training/weeks/${weekNumber}`)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  if (overview.isPending) return <Typography role="status">Loading workout editor…</Typography>
  if (overview.isError) return <Alert severity="error">{overview.error.message}</Alert>
  if (!overview.data) return <Alert severity="error">Create an active program before adding a workout.</Alert>
  const latestWeekNumber = Math.max(...overview.data.availableWeekNumbers)
  const addWorkoutWeekNumber = overview.data.currentWeekNumber ?? latestWeekNumber + 1
  if (addWorkoutWeekNumber !== weekNumber) {
    return (
      <Stack spacing={2}>
        <Alert severity="info">Add workouts to Week {addWorkoutWeekNumber}.</Alert>
        <Button component={Link} to={`/training/weeks/${overview.data.selectedWeekNumber}`}>
          Return to training
        </Button>
      </Stack>
    )
  }

  return (
    <Stack component="form" onSubmit={submit} spacing={3}>
      <Button
        component={Link}
        startIcon={<ArrowBackIcon />}
        sx={{ alignSelf: 'flex-start' }}
        to={`/training/weeks/${overview.data.selectedWeekNumber}`}
      >
        Training
      </Button>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ alignItems: { sm: 'flex-end' }, justifyContent: 'space-between' }}
      >
        <Stack spacing={0.5}>
          <Typography color="text.secondary" variant="overline">{overview.data.program.name}</Typography>
          <Typography component="h1" variant="h4">Add Workout</Typography>
          <Typography color="text.secondary" variant="body2">Week {weekNumber}</Typography>
        </Stack>
        <Button disabled={create.isPending} type="submit" variant="contained" size="large">
          {create.isPending ? 'Saving…' : 'Save Workout'}
        </Button>
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
      {exercises.isError && <Alert severity="error">Existing exercises could not be loaded.</Alert>}

      <Paper component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
        <Stack spacing={2}>
          <TextField
            autoFocus
            label="Workout name"
            onChange={(event) => setWorkout((current) => ({ ...current, name: event.target.value }))}
            required
            value={workout.name}
          />
          <TextField
            label="Workout note (optional)"
            multiline
            onChange={(event) => setWorkout((current) => ({ ...current, note: event.target.value }))}
            rows={2}
            value={workout.note}
          />
        </Stack>
      </Paper>

      {workout.groups.map((group, groupIndex) => (
        <Paper key={groupIndex} component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Stack spacing={2.25}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: '1fr 1fr auto' }, gap: 1.25 }}>
              <TextField
                label="Group label"
                onChange={(event) => updateGroup(groupIndex, (item) => ({ ...item, label: event.target.value }))}
                required
                value={group.label}
              />
              <TextField
                label="Group kind"
                onChange={(event) => updateGroup(groupIndex, (item) => ({ ...item, kind: event.target.value }))}
                select
                value={group.kind}
              >
                <MenuItem value="STRAIGHT_SET">Straight set</MenuItem>
                <MenuItem value="SUPERSET">Superset</MenuItem>
              </TextField>
              {workout.groups.length > 1 && (
                <Button
                  color="error"
                  onClick={() => setWorkout((current) => ({
                    ...current,
                    groups: current.groups.filter((_, index) => index !== groupIndex),
                  }))}
                  startIcon={<RemoveIcon />}
                >
                  Remove group
                </Button>
              )}
            </Box>

            {group.prescriptions.map((prescription, prescriptionIndex) => (
              <Stack key={prescriptionIndex} spacing={1.5}>
                {prescriptionIndex > 0 && <Divider />}
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Typography component="h2" variant="h6">Movement {prescriptionIndex + 1}</Typography>
                  {group.prescriptions.length > 1 && (
                    <Button
                      color="error"
                      onClick={() => updateGroup(groupIndex, (item) => ({
                        ...item,
                        prescriptions: item.prescriptions.filter((_, index) => index !== prescriptionIndex),
                      }))}
                    >
                      Remove movement
                    </Button>
                  )}
                </Stack>
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '2fr 1fr' }, gap: 1.25 }}>
                  <TextField
                    disabled={exercises.isPending}
                    label="Exercise — select or create"
                    onChange={(event) => selectExercise(groupIndex, prescriptionIndex, event.target.value)}
                    required
                    select
                    value={prescription.exerciseChoice}
                  >
                    {(exercises.data ?? []).map((item) => (
                      <MenuItem key={item.id} value={`existing:${item.id}`}>{item.name}</MenuItem>
                    ))}
                    <MenuItem value="new">Create a new exercise…</MenuItem>
                  </TextField>
                  <TextField
                    label="Execution type — confirm"
                    onChange={(event) => updatePrescription(groupIndex, prescriptionIndex, 'executionType', event.target.value)}
                    required
                    select
                    value={prescription.executionType}
                  >
                    <MenuItem value="REPS">Reps</MenuItem>
                    <MenuItem value="REPS_PER_SIDE">Reps per side</MenuItem>
                    <MenuItem value="DURATION">Duration</MenuItem>
                  </TextField>
                </Box>
                {prescription.createExercise && (
                  <TextField
                    label="New exercise name"
                    onChange={(event) => updatePrescription(groupIndex, prescriptionIndex, 'exerciseName', event.target.value)}
                    required
                    value={prescription.exerciseName}
                  />
                )}
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' }, gap: 1 }}>
                  {[
                    ['sets', 'Sets'],
                    ['reps', 'Reps / time'],
                    ['load', 'Load'],
                    ['rir', 'RIR'],
                    ['rest', 'Rest'],
                    ['tempo', 'Tempo'],
                  ].map(([field, label]) => (
                    <TextField
                      key={field}
                      label={label}
                      onChange={(event) => updatePrescription(groupIndex, prescriptionIndex, field, event.target.value)}
                      value={prescription[field]}
                    />
                  ))}
                </Box>
                {prescription.createExercise && (
                  <TextField
                    label="Demo URL (optional)"
                    onChange={(event) => updatePrescription(groupIndex, prescriptionIndex, 'demoUrl', event.target.value)}
                    value={prescription.demoUrl}
                  />
                )}
                <TextField
                  label="Cues / prescription note (optional)"
                  multiline
                  onChange={(event) => updatePrescription(groupIndex, prescriptionIndex, 'note', event.target.value)}
                  rows={2}
                  value={prescription.note}
                />
              </Stack>
            ))}

            <Button
              onClick={() => updateGroup(groupIndex, (item) => ({
                ...item,
                prescriptions: [...item.prescriptions, emptyPrescription()],
              }))}
              startIcon={<AddIcon />}
              sx={{ alignSelf: 'flex-start' }}
            >
              Add movement
            </Button>
          </Stack>
        </Paper>
      ))}

      <Button
        onClick={() => setWorkout((current) => ({
          ...current,
          groups: [...current.groups, emptyGroup(current.groups.length)],
        }))}
        startIcon={<AddIcon />}
        sx={{ alignSelf: 'flex-start' }}
        variant="outlined"
      >
        Add group
      </Button>

      <Button disabled={create.isPending} type="submit" variant="contained" size="large" sx={{ alignSelf: { sm: 'flex-end' } }}>
        {create.isPending ? 'Saving…' : 'Save Workout'}
      </Button>
    </Stack>
  )
}
