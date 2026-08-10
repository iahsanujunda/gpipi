import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowBackIcon, EditIcon, RemoveIcon } from '@/app/AppIcons'
import {
  useDeleteTrainingSet,
  usePutTrainingSet,
  useTrainingLifecycle,
  useUpdateTrainingSession,
  useWorkoutDetail,
} from './queries'

function todayInTokyo() {
  const parts = new Intl.DateTimeFormat('en', {
    day: '2-digit',
    month: '2-digit',
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
  }).formatToParts(new Date())
  const part = (type) => parts.find((candidate) => candidate.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function formatPerformedDate(value) {
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00Z`))
}

function targetSummary(exercise) {
  return [
    exercise.targetSets ? `${exercise.targetSets} sets` : null,
    exercise.targetReps,
    exercise.targetLoad,
    exercise.targetRir ? `RIR ${exercise.targetRir}` : null,
    exercise.targetRest ? `${exercise.targetRest} rest` : null,
  ].filter(Boolean).join(' · ')
}

function executionLabel(type) {
  if (type === 'DURATION') return 'Seconds'
  if (type === 'REPS_PER_SIDE') return 'Reps / side'
  return 'Reps'
}

function emptyFields() {
  return { primary: '', load: '', rir: '', note: '' }
}

function setsVersion(exercise) {
  return exercise.sets
    .map((item) => `${item.id}:${item.setNumber}:${item.reps}:${item.durationSeconds}:${item.load}:${item.rir}`)
    .join('|')
}

function SetEditor({ exercise, isPending, onDelete, onSave }) {
  const slots = new Set(exercise.sets.map((item) => item.setNumber))
  let missingSlot = 1
  while (slots.has(missingSlot)) missingSlot += 1
  const nextSlot = Math.max(0, ...exercise.sets.map((item) => item.setNumber)) + 1
  const hasGap = missingSlot < nextSlot
  const [setNumber, setSetNumber] = useState(missingSlot)
  const [fields, setFields] = useState(emptyFields)
  const [editing, setEditing] = useState(false)

  function editSet(item) {
    setSetNumber(item.setNumber)
    setFields({
      primary: String(item.durationSeconds ?? item.reps ?? ''),
      load: item.load ?? '',
      rir: item.rir == null ? '' : String(item.rir),
      note: item.note ?? '',
    })
    setEditing(true)
  }

  async function submit(event) {
    event.preventDefault()
    const primary = Number.parseInt(fields.primary, 10)
    if (!Number.isInteger(primary)) return
    await onSave(exercise, setNumber, {
      reps: exercise.executionType === 'DURATION' ? null : primary,
      durationSeconds: exercise.executionType === 'DURATION' ? primary : null,
      load: fields.load.trim() || null,
      rir: fields.rir === '' ? null : Number.parseInt(fields.rir, 10),
      note: fields.note.trim() || null,
    })
  }

  async function remove(item) {
    await onDelete(exercise, item.setNumber)
    if (setNumber === item.setNumber) {
      setFields(emptyFields())
      setEditing(false)
    }
  }

  return (
    <Stack spacing={1.25}>
      {exercise.sets.map((item) => (
        <Paper key={item.id} variant="outlined" sx={{ px: 1.25, py: 1, bgcolor: 'background.default' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Box sx={{ display: 'grid', placeItems: 'center', width: 32, height: 32, flex: '0 0 auto', borderRadius: '50%', bgcolor: 'highlight.main', color: 'text.heading', fontWeight: 750 }}>
              {item.setNumber}
            </Box>
            <Stack spacing={0.1} sx={{ flexGrow: 1, minWidth: 0 }}>
              <Typography sx={{ fontSize: '0.875rem', fontWeight: 700 }}>
                {item.durationSeconds == null ? `${item.reps} reps` : `${item.durationSeconds} sec`}
              </Typography>
              <Typography color="text.secondary" variant="body2">
                {[item.load ? `${item.load} kg` : null, item.rir == null ? null : `RIR ${item.rir}`].filter(Boolean).join(' · ') || 'No load or RIR logged'}
              </Typography>
            </Stack>
            <IconButton aria-label={`Edit set ${item.setNumber} for ${exercise.exerciseName}`} onClick={() => editSet(item)}>
              <EditIcon />
            </IconButton>
            <IconButton aria-label={`Delete set ${item.setNumber} for ${exercise.exerciseName}`} disabled={isPending} onClick={() => remove(item)}>
              <RemoveIcon />
            </IconButton>
          </Stack>
        </Paper>
      ))}

      <Box component="form" onSubmit={submit} aria-label={`Set editor for ${exercise.exerciseName}`}>
        <Stack spacing={1.25}>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
            <Typography component="h4" sx={{ color: 'text.heading', fontSize: '0.875rem', fontWeight: 700 }}>
              {editing ? `Edit Set ${setNumber}` : `Set ${setNumber}`}
            </Typography>
            <Typography color="text.secondary" sx={{ fontSize: '0.75rem' }}>
              {editing ? 'Logged values' : 'Empty until logged'}
            </Typography>
          </Stack>
          {hasGap && !editing && (
            <Stack direction="row" spacing={1}>
              <Button
                aria-pressed={setNumber === missingSlot}
                onClick={() => setSetNumber(missingSlot)}
                size="small"
                variant={setNumber === missingSlot ? 'contained' : 'outlined'}
              >
                Correct Set {missingSlot}
              </Button>
              <Button
                aria-pressed={setNumber === nextSlot}
                onClick={() => setSetNumber(nextSlot)}
                size="small"
                variant={setNumber === nextSlot ? 'contained' : 'outlined'}
              >
                Log new Set {nextSlot}
              </Button>
            </Stack>
          )}
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr) minmax(0, 1fr)', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1 }}>
            <TextField
              label={executionLabel(exercise.executionType)}
              name="primary"
              onChange={(event) => setFields((current) => ({ ...current, primary: event.target.value }))}
              slotProps={{ htmlInput: { inputMode: 'numeric', min: 0 } }}
              type="number"
              value={fields.primary}
            />
            <TextField
              label="Load kg"
              name="load"
              onChange={(event) => setFields((current) => ({ ...current, load: event.target.value }))}
              slotProps={{ htmlInput: { inputMode: 'decimal', min: 0, step: 'any' } }}
              type="number"
              value={fields.load}
            />
            <TextField
              label="RIR"
              name="rir"
              onChange={(event) => setFields((current) => ({ ...current, rir: event.target.value }))}
              slotProps={{ htmlInput: { inputMode: 'numeric', min: 0 } }}
              type="number"
              value={fields.rir}
            />
          </Box>
          <TextField
            label="Set note (optional)"
            name="note"
            onChange={(event) => setFields((current) => ({ ...current, note: event.target.value }))}
            value={fields.note}
          />
          <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
            {editing && (
              <Button onClick={() => { setSetNumber(missingSlot); setFields(emptyFields()); setEditing(false) }}>
                Cancel edit
              </Button>
            )}
            <Button disabled={!fields.primary || isPending} type="submit" variant="contained">
              {editing ? `Save Set ${setNumber}` : `Log Set ${setNumber}`}
            </Button>
          </Stack>
        </Stack>
      </Box>
    </Stack>
  )
}

function WorkoutMetadata({ onSave, pending, session, weekId }) {
  const [performedOn, setPerformedOn] = useState(session?.performedOn ?? todayInTokyo())
  const [sessionNote, setSessionNote] = useState(session?.note ?? '')

  return (
    <Paper component="section" aria-label="Workout details" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
      <Stack spacing={1.5}>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: 'minmax(0, 220px) minmax(0, 1fr)' }, gap: 1.25 }}>
          <TextField
            label="Performed on"
            onChange={(event) => setPerformedOn(event.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
            type="date"
            value={performedOn}
          />
          <TextField label="Workout note (optional)" onChange={(event) => setSessionNote(event.target.value)} value={sessionNote} />
        </Box>
        <Button
          disabled={pending}
          onClick={() => onSave({
            weekId,
            session: { performedOn, note: sessionNote || null },
          })}
          sx={{ alignSelf: 'flex-end' }}
        >
          Save workout details
        </Button>
      </Stack>
    </Paper>
  )
}

function WorkoutLoading() {
  return (
    <Stack aria-label="Loading workout" role="status" spacing={2}>
      <Skeleton width={110} />
      <Skeleton width={250} height={48} />
      <Skeleton variant="rounded" height={320} />
    </Stack>
  )
}

export default function WorkoutPage() {
  const { weekNumber, workoutId } = useParams()
  const navigate = useNavigate()
  const detail = useWorkoutDetail(Number.parseInt(weekNumber, 10), workoutId)
  const putSet = usePutTrainingSet()
  const deleteSet = useDeleteTrainingSet()
  const updateSession = useUpdateTrainingSession()
  const finish = useTrainingLifecycle('finish')
  const resume = useTrainingLifecycle('resume')
  const restore = useTrainingLifecycle('restore')
  const [notice, setNotice] = useState(null)

  if (detail.isPending) return <WorkoutLoading />
  if (detail.isError) return <Alert severity="error">{detail.error.message}</Alert>

  const workout = detail.data
  const completed = workout.session?.status === 'COMPLETED'
  const pending = putSet.isPending || deleteSet.isPending || finish.isPending || resume.isPending

  async function run(label, operation) {
    try {
      await operation()
      setNotice(label)
    } catch (error) {
      setNotice(error.message)
    }
  }

  function saveSet(exercise, setNumber, set) {
    return run(`Set ${setNumber} saved`, () => putSet.mutateAsync({
      weekId: workout.weekId,
      prescriptionId: exercise.prescriptionId,
      setNumber,
      set,
    }))
  }

  function removeSet(exercise, setNumber) {
    return run(`Set ${setNumber} removed`, () => deleteSet.mutateAsync({
      weekId: workout.weekId,
      prescriptionId: exercise.prescriptionId,
      setNumber,
    }))
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Button component={Link} to={`/training/weeks/${workout.weekNumber}`} startIcon={<ArrowBackIcon />}>
          Week {workout.weekNumber}
        </Button>
        {workout.currentWeekNumber && workout.currentWeekNumber !== workout.weekNumber && (
          <Button onClick={() => navigate(`/training/weeks/${workout.currentWeekNumber}`)} variant="outlined">
            Current · Week {workout.currentWeekNumber}
          </Button>
        )}
      </Stack>

      <Stack spacing={1}>
        <Typography color="text.secondary" sx={{ fontSize: '0.75rem', fontWeight: 750, letterSpacing: '0.08em', textTransform: 'uppercase' }}>
          {workout.program.name} · Week {workout.weekNumber}
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}>
          <Typography component="h1" variant="h4">{completed ? 'Workout history' : workout.workoutName}</Typography>
          <Chip label={workout.skipped ? 'Skipped' : completed ? 'Completed' : workout.session ? 'In progress' : 'Not started'} sx={{ alignSelf: 'flex-start', textTransform: 'uppercase' }} />
        </Stack>
        {completed && (
          <Typography color="text.secondary">
            {workout.workoutName} · performed {formatPerformedDate(workout.session.performedOn)}
          </Typography>
        )}
        {workout.workoutNote && <Typography color="text.secondary">{workout.workoutNote}</Typography>}
      </Stack>

      {notice && <Alert role="status" severity="info" onClose={() => setNotice(null)}>{notice}</Alert>}

      {completed && (
        <Alert severity="info">
          Historical targets are preserved from the first entry. Editing a set leaves this workout completed.
        </Alert>
      )}

      <WorkoutMetadata
        key={`${workout.session?.id ?? 'new'}:${workout.session?.updatedAt ?? ''}`}
        onSave={(input) => run('Workout details saved', () => updateSession.mutateAsync(input))}
        pending={updateSession.isPending}
        session={workout.session}
        weekId={workout.weekId}
      />

      <Stack spacing={2}>
        {workout.groups.map((group) => (
          <Paper key={`${group.position}-${group.label}`} component="section" variant="outlined" sx={{ overflow: 'hidden' }}>
            <Box sx={{ px: 2.25, py: 1.25, bgcolor: group.kind === 'SUPERSET' ? 'text.heading' : 'primary.main', color: 'primary.contrastText' }}>
              <Typography sx={{ fontSize: '0.75rem', fontWeight: 750, letterSpacing: '0.08em', textTransform: 'uppercase' }}>
                {group.kind === 'SUPERSET' ? 'Superset' : 'Straight set'} · {group.label}
              </Typography>
            </Box>
            <Stack divider={<Divider flexItem />}>
              {group.exercises.map((exercise) => (
                <Stack key={exercise.prescriptionId} spacing={1.75} sx={{ p: { xs: 2, sm: 2.5 } }}>
                  <Stack spacing={0.6}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                      <Typography component="h3" variant="h6">{exercise.exerciseName}</Typography>
                      {exercise.demoUrl && (
                        <Button component="a" href={exercise.demoUrl} rel="noreferrer" size="small" target="_blank">
                          Demo
                        </Button>
                      )}
                    </Stack>
                    <Typography color="text.secondary" variant="body2">{targetSummary(exercise) || 'Prescribed movement'}</Typography>
                    <Chip label={executionLabel(exercise.executionType)} size="small" sx={{ alignSelf: 'flex-start', textTransform: 'uppercase' }} />
                    {exercise.targetTempo && <Typography variant="body2">Tempo {exercise.targetTempo}</Typography>}
                    {exercise.targetNote && (
                      <Box component="details">
                        <Typography component="summary" sx={{ color: 'primary.main', cursor: 'pointer', fontSize: '0.875rem', fontWeight: 700 }}>
                          Cues
                        </Typography>
                        <Typography color="text.secondary" variant="body2" sx={{ mt: 1 }}>{exercise.targetNote}</Typography>
                      </Box>
                    )}
                  </Stack>
                  <SetEditor
                    key={`${exercise.prescriptionId}:${setsVersion(exercise)}`}
                    exercise={exercise}
                    isPending={pending}
                    onDelete={removeSet}
                    onSave={saveSet}
                  />
                </Stack>
              ))}
            </Stack>
          </Paper>
        ))}
      </Stack>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} sx={{ alignItems: { sm: 'center' } }}>
        {workout.skipped ? (
          <Button disabled={restore.isPending} onClick={() => run('Workout week restored', () => restore.mutateAsync(workout.weekId))} variant="outlined">
            Restore workout week
          </Button>
        ) : completed ? (
          <Button disabled={pending} onClick={() => run('Workout resumed', () => resume.mutateAsync(workout.weekId))} variant="outlined">
            Resume workout
          </Button>
        ) : (
          <Button disabled={pending} onClick={() => run('Workout finished', () => finish.mutateAsync(workout.weekId))} variant="contained">
            Finish workout
          </Button>
        )}
        <Typography color="text.secondary" variant="body2">
          Sets save immediately. Finishing does not validate against the prescription.
        </Typography>
      </Stack>
    </Stack>
  )
}
