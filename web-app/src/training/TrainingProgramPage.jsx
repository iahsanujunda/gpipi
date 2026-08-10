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
import { Link, useNavigate } from 'react-router'
import { AddIcon, ArrowBackIcon, RemoveIcon } from '@/app/AppIcons'
import {
  useActivateTrainingProgram,
  useCreateTrainingProgram,
  useTrainingExercises,
  useTrainingOverview,
  useTrainingPrograms,
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

const emptyWeek = (weekNumber = 1) => ({
  weekNumber,
  groups: [emptyGroup()],
})

const emptyWorkout = (position = 0) => ({
  name: `Workout ${position + 1}`,
  note: '',
  weeks: [emptyWeek()],
})

function replaceAt(items, index, value) {
  return items.map((item, itemIndex) => itemIndex === index ? value : item)
}

function nullable(value) {
  return value.trim() || null
}

function sanitizeProgram(program) {
  return {
    name: program.name,
    note: nullable(program.note),
    startsOn: program.startsOn || null,
    workouts: program.workouts.map((workout) => ({
      name: workout.name,
      note: nullable(workout.note),
      weeks: workout.weeks.map((week) => ({
        weekNumber: Number(week.weekNumber),
        groups: week.groups.map((group) => ({
          label: group.label,
          kind: group.kind,
          prescriptions: group.prescriptions.map((prescription) => (
            {
              exerciseName: prescription.exerciseName,
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
            }
          )),
        })),
      })),
    })),
  }
}

export default function TrainingProgramPage() {
  const navigate = useNavigate()
  const overview = useTrainingOverview()
  const exercises = useTrainingExercises()
  const programs = useTrainingPrograms()
  const create = useCreateTrainingProgram()
  const activate = useActivateTrainingProgram()
  const [error, setError] = useState(null)
  const [program, setProgram] = useState({
    name: '',
    note: '',
    startsOn: '',
    workouts: [emptyWorkout()],
  })

  function updateWorkout(workoutIndex, transform) {
    setProgram((current) => ({
      ...current,
      workouts: replaceAt(current.workouts, workoutIndex, transform(current.workouts[workoutIndex])),
    }))
  }

  function updateWeek(workoutIndex, weekIndex, transform) {
    updateWorkout(workoutIndex, (workout) => ({
      ...workout,
      weeks: replaceAt(workout.weeks, weekIndex, transform(workout.weeks[weekIndex])),
    }))
  }

  function updateGroup(workoutIndex, weekIndex, groupIndex, transform) {
    updateWeek(workoutIndex, weekIndex, (week) => ({
      ...week,
      groups: replaceAt(week.groups, groupIndex, transform(week.groups[groupIndex])),
    }))
  }

  function updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, field, value) {
    updateGroup(workoutIndex, weekIndex, groupIndex, (group) => ({
      ...group,
      prescriptions: replaceAt(
        group.prescriptions,
        prescriptionIndex,
        { ...group.prescriptions[prescriptionIndex], [field]: value },
      ),
    }))
  }

  function selectExercise(workoutIndex, weekIndex, groupIndex, prescriptionIndex, choice) {
    const existingId = choice.startsWith('existing:') ? choice.slice('existing:'.length) : null
    const existing = exercises.data?.find((exercise) => exercise.id === existingId)
    updateGroup(workoutIndex, weekIndex, groupIndex, (group) => ({
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

  function duplicateWeek(workoutIndex, weekIndex) {
    updateWorkout(workoutIndex, (workout) => {
      const source = workout.weeks[weekIndex]
      const nextNumber = Math.max(...workout.weeks.map((week) => Number(week.weekNumber))) + 1
      const copy = {
        ...source,
        weekNumber: nextNumber,
        groups: source.groups.map((group) => ({
          ...group,
          prescriptions: group.prescriptions.map((prescription) => ({ ...prescription })),
        })),
      }
      return { ...workout, weeks: [...workout.weeks, copy] }
    })
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    try {
      await create.mutateAsync(sanitizeProgram(program))
      navigate('/training')
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  return (
    <Stack component="form" onSubmit={submit} spacing={3}>
      <Button component={Link} startIcon={<ArrowBackIcon />} sx={{ alignSelf: 'flex-start' }} to="/training">
        Training
      </Button>

      <Stack spacing={0.75}>
        <Typography component="h1" variant="h4">Training program</Typography>
        <Typography color="text.secondary">
          Human-reviewed authoring for an open-ended block. Add only the weeks your trainer has prescribed.
        </Typography>
      </Stack>

      {overview.data && (
        <Alert severity="info">
          {overview.data.program.name} is active. Saving this form starts another program and makes it active.
        </Alert>
      )}
      {error && <Alert severity="error">{error}</Alert>}
      {exercises.isError && <Alert severity="error">Existing exercises could not be loaded.</Alert>}

      {(programs.data?.length ?? 0) > 0 && (
        <Paper component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Stack spacing={1.5}>
            <Typography component="h2" variant="h6">Saved programs</Typography>
            {programs.data.map((item) => (
              <Stack key={item.id} direction="row" spacing={1.5} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                <Stack spacing={0.25}>
                  <Typography sx={{ fontWeight: 700 }}>{item.name}</Typography>
                  <Typography color="text.secondary" variant="body2">
                    {item.active ? 'Active program' : 'Inactive · history retained'}
                  </Typography>
                </Stack>
                {item.active ? (
                  <Typography color="primary.main" sx={{ fontSize: '0.75rem', fontWeight: 750, textTransform: 'uppercase' }}>Active</Typography>
                ) : (
                  <Button
                    disabled={activate.isPending}
                    onClick={async () => {
                      setError(null)
                      try {
                        await activate.mutateAsync(item.id)
                        navigate('/training')
                      } catch (requestError) {
                        setError(requestError.message)
                      }
                    }}
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

      <Paper component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
        <Stack spacing={2}>
          <Typography component="h2" variant="h6">Program details</Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: '2fr 1fr' }, gap: 1.5 }}>
            <TextField
              label="Program name"
              onChange={(event) => setProgram((current) => ({ ...current, name: event.target.value }))}
              required
              value={program.name}
            />
            <TextField
              label="Starts on (optional)"
              onChange={(event) => setProgram((current) => ({ ...current, startsOn: event.target.value }))}
              slotProps={{ inputLabel: { shrink: true } }}
              type="date"
              value={program.startsOn}
            />
          </Box>
          <TextField
            label="Program note (optional)"
            multiline
            onChange={(event) => setProgram((current) => ({ ...current, note: event.target.value }))}
            rows={2}
            value={program.note}
          />
        </Stack>
      </Paper>

      {program.workouts.map((workout, workoutIndex) => (
        <Paper key={workoutIndex} component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Stack spacing={2.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: { sm: 'center' } }}>
              <Typography component="h2" variant="h6" sx={{ minWidth: 100 }}>Workout {workoutIndex + 1}</Typography>
              <TextField
                fullWidth
                label="Workout name"
                onChange={(event) => updateWorkout(workoutIndex, (item) => ({ ...item, name: event.target.value }))}
                required
                value={workout.name}
              />
              {program.workouts.length > 1 && (
                <Button
                  color="error"
                  onClick={() => setProgram((current) => ({ ...current, workouts: current.workouts.filter((_, index) => index !== workoutIndex) }))}
                  startIcon={<RemoveIcon />}
                >
                  Remove workout
                </Button>
              )}
            </Stack>
            <TextField
              label="Workout note (optional)"
              onChange={(event) => updateWorkout(workoutIndex, (item) => ({ ...item, note: event.target.value }))}
              value={workout.note}
            />

            {workout.weeks.map((week, weekIndex) => (
              <Paper key={weekIndex} component="section" variant="outlined" sx={{ p: { xs: 1.5, sm: 2 }, bgcolor: 'background.default' }}>
                <Stack spacing={2}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                    <TextField
                      label="Week"
                      onChange={(event) => updateWeek(workoutIndex, weekIndex, (item) => ({ ...item, weekNumber: event.target.value }))}
                      required
                      slotProps={{ htmlInput: { min: 1 } }}
                      sx={{ width: 110 }}
                      type="number"
                      value={week.weekNumber}
                    />
                    <Button onClick={() => duplicateWeek(workoutIndex, weekIndex)} variant="outlined">
                      Duplicate this week
                    </Button>
                    {workout.weeks.length > 1 && (
                      <Button
                        color="error"
                        onClick={() => updateWorkout(workoutIndex, (item) => ({ ...item, weeks: item.weeks.filter((_, index) => index !== weekIndex) }))}
                      >
                        Remove week
                      </Button>
                    )}
                  </Stack>

                  {week.groups.map((group, groupIndex) => (
                    <Paper key={groupIndex} variant="outlined" sx={{ p: { xs: 1.5, sm: 2 } }}>
                      <Stack spacing={2}>
                        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: '1fr 1fr auto' }, gap: 1.25 }}>
                          <TextField
                            label="Group label"
                            onChange={(event) => updateGroup(workoutIndex, weekIndex, groupIndex, (item) => ({ ...item, label: event.target.value }))}
                            required
                            value={group.label}
                          />
                          <TextField
                            label="Group kind"
                            onChange={(event) => updateGroup(workoutIndex, weekIndex, groupIndex, (item) => ({ ...item, kind: event.target.value }))}
                            select
                            value={group.kind}
                          >
                            <MenuItem value="STRAIGHT_SET">Straight set</MenuItem>
                            <MenuItem value="SUPERSET">Superset</MenuItem>
                          </TextField>
                          {week.groups.length > 1 && (
                            <Button
                              color="error"
                              onClick={() => updateWeek(workoutIndex, weekIndex, (item) => ({ ...item, groups: item.groups.filter((_, index) => index !== groupIndex) }))}
                            >
                              Remove group
                            </Button>
                          )}
                        </Box>

                        {group.prescriptions.map((prescription, prescriptionIndex) => (
                          <Stack key={prescriptionIndex} spacing={1.5}>
                            {prescriptionIndex > 0 && <Divider />}
                            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                              <Typography component="h3" sx={{ color: 'text.heading', fontWeight: 700 }}>
                                Movement {prescriptionIndex + 1}
                              </Typography>
                              {group.prescriptions.length > 1 && (
                                <Button
                                  color="error"
                                  onClick={() => updateGroup(workoutIndex, weekIndex, groupIndex, (item) => ({
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
                                onChange={(event) => selectExercise(workoutIndex, weekIndex, groupIndex, prescriptionIndex, event.target.value)}
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
                                onChange={(event) => updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, 'executionType', event.target.value)}
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
                                onChange={(event) => updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, 'exerciseName', event.target.value)}
                                required
                                value={prescription.exerciseName}
                              />
                            )}
                            {prescription.exerciseId && (
                              <Typography color="text.secondary" variant="body2">
                                Reusing {prescription.exerciseName}; aliases remain attached to the same movement history.
                              </Typography>
                            )}
                            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(6, minmax(0, 1fr))' }, gap: 1 }}>
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
                                  onChange={(event) => updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, field, event.target.value)}
                                  value={prescription[field]}
                                />
                              ))}
                            </Box>
                            {prescription.createExercise && (
                              <TextField
                                label="Demo URL (optional)"
                                onChange={(event) => updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, 'demoUrl', event.target.value)}
                                value={prescription.demoUrl}
                              />
                            )}
                            <TextField
                              label="Cues / prescription note (optional)"
                              multiline
                              onChange={(event) => updatePrescription(workoutIndex, weekIndex, groupIndex, prescriptionIndex, 'note', event.target.value)}
                              rows={2}
                              value={prescription.note}
                            />
                          </Stack>
                        ))}
                        <Button
                          onClick={() => updateGroup(workoutIndex, weekIndex, groupIndex, (item) => ({ ...item, prescriptions: [...item.prescriptions, emptyPrescription()] }))}
                          startIcon={<AddIcon />}
                          sx={{ alignSelf: 'flex-start' }}
                        >
                          Add movement
                        </Button>
                      </Stack>
                    </Paper>
                  ))}
                  <Button
                    onClick={() => updateWeek(workoutIndex, weekIndex, (item) => ({ ...item, groups: [...item.groups, emptyGroup(item.groups.length)] }))}
                    startIcon={<AddIcon />}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    Add group
                  </Button>
                </Stack>
              </Paper>
            ))}
          </Stack>
        </Paper>
      ))}

      <Button
        onClick={() => setProgram((current) => ({ ...current, workouts: [...current.workouts, emptyWorkout(current.workouts.length)] }))}
        startIcon={<AddIcon />}
        sx={{ alignSelf: 'flex-start' }}
        variant="outlined"
      >
        Add workout
      </Button>

      <Paper sx={{ position: 'sticky', bottom: 'calc(72px + env(safe-area-inset-bottom))', zIndex: 2, p: 1.5, border: 1, borderColor: 'divider' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}>
          <Typography color="text.secondary" variant="body2">
            Review every prescribed field before creating the active program.
          </Typography>
          <Button disabled={create.isPending} type="submit" variant="contained">
            {create.isPending ? 'Creating…' : 'Create active program'}
          </Button>
        </Stack>
      </Paper>
    </Stack>
  )
}
