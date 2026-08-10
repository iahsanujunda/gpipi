import { useEffect, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material'
import { Link, useNavigate, useParams } from 'react-router'
import { ChevronLeftIcon, ChevronRightIcon } from '@/app/AppIcons'
import { useTrainingLifecycle, useTrainingOverview } from './queries'

const statusLabels = {
  COMPLETED: 'Completed',
  IN_PROGRESS: 'In progress',
  NOT_STARTED: 'Not started',
  SKIPPED: 'Skipped',
}

function formatDate(value) {
  if (!value) return null
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`))
}

function WorkoutCard({ selectedWeek, workout, onLifecycle, lifecyclePending }) {
  const action = workout.status === 'COMPLETED'
    ? 'Review'
    : workout.status === 'IN_PROGRESS' ? 'Continue' : 'Open'
  const detail = workout.status === 'NOT_STARTED'
    ? 'Prescription ready to review'
    : workout.status === 'SKIPPED'
      ? 'Kept in history'
      : [
          workout.performedOn ? `Performed ${formatDate(workout.performedOn)}` : null,
          `${workout.setCount} ${workout.setCount === 1 ? 'set' : 'sets'} logged`,
        ].filter(Boolean).join(' · ')

  return (
    <Paper component="article" variant="outlined" sx={{ p: { xs: 2.25, sm: 2.5 }, minWidth: 0 }}>
      <Stack spacing={1.75}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
          <Stack spacing={0.75} sx={{ flexGrow: 1, minWidth: 0 }}>
            <Chip
              label={statusLabels[workout.status] ?? workout.status}
              size="small"
              sx={{ alignSelf: 'flex-start', textTransform: 'uppercase' }}
            />
            <Typography component="h3" variant="h6">{workout.workoutName}</Typography>
            <Typography color="text.secondary" variant="body2">{detail}</Typography>
          </Stack>
          <Button
            component={Link}
            to={`/training/weeks/${selectedWeek}/workouts/${workout.workoutId}`}
            variant={workout.status === 'IN_PROGRESS' ? 'contained' : 'outlined'}
            sx={{ flexShrink: 0 }}
          >
            {action}
          </Button>
        </Stack>
        {workout.status === 'NOT_STARTED' && (
          <Button
            disabled={lifecyclePending}
            onClick={() => onLifecycle('skip', workout)}
            size="small"
            sx={{ alignSelf: 'flex-start' }}
          >
            Skip this workout week
          </Button>
        )}
        {workout.status === 'SKIPPED' && (
          <Button
            disabled={lifecyclePending}
            onClick={() => onLifecycle('restore', workout)}
            size="small"
            sx={{ alignSelf: 'flex-start' }}
          >
            Restore this workout week
          </Button>
        )}
      </Stack>
    </Paper>
  )
}

function TrainingLoading() {
  return (
    <Stack aria-label="Loading training" role="status" spacing={2}>
      <Skeleton width={180} height={46} />
      <Skeleton variant="rounded" height={72} />
      <Skeleton variant="rounded" height={150} />
      <Skeleton variant="rounded" height={150} />
    </Stack>
  )
}

export default function TrainingPage() {
  const { weekNumber: routeWeek } = useParams()
  const requestedWeek = routeWeek ? Number.parseInt(routeWeek, 10) : undefined
  const navigate = useNavigate()
  const overview = useTrainingOverview(Number.isInteger(requestedWeek) ? requestedWeek : undefined)
  const skip = useTrainingLifecycle('skip')
  const restore = useTrainingLifecycle('restore')
  const [notice, setNotice] = useState(null)

  useEffect(() => {
    if (!routeWeek && overview.data?.selectedWeekNumber) {
      navigate(`/training/weeks/${overview.data.selectedWeekNumber}`, { replace: true })
    }
  }, [navigate, overview.data?.selectedWeekNumber, routeWeek])

  if (overview.isPending) return <TrainingLoading />
  if (overview.isError) {
    return <Alert severity="error">{overview.error.message}</Alert>
  }
  if (!overview.data) {
    return (
      <Stack spacing={2.5} sx={{ maxWidth: 620 }}>
        <Typography component="h1" variant="h4">Training</Typography>
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Stack spacing={1.5}>
            <Typography component="h2" variant="h6">No active program</Typography>
            <Typography color="text.secondary">
              Author the prescribed workouts and weeks before logging execution.
            </Typography>
            <Button component={Link} to="/training/program" variant="contained" sx={{ alignSelf: 'flex-start' }}>
              Create training program
            </Button>
          </Stack>
        </Paper>
      </Stack>
    )
  }

  const data = overview.data
  const index = data.availableWeekNumbers.indexOf(data.selectedWeekNumber)
  const previous = data.availableWeekNumbers[index - 1]
  const next = data.availableWeekNumbers[index + 1]
  const isCurrent = data.selectedWeekNumber === data.currentWeekNumber
  const resolved = data.workouts.filter((item) => ['COMPLETED', 'SKIPPED'].includes(item.status)).length

  async function lifecycle(action, workout) {
    const mutation = action === 'skip' ? skip : restore
    try {
      await mutation.mutateAsync(workout.weekId)
      setNotice(action === 'skip' ? `${workout.workoutName} skipped` : `${workout.workoutName} restored`)
    } catch (error) {
      setNotice(error.message)
    }
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <Stack spacing={0.75} sx={{ minWidth: 0 }}>
          <Typography component="h1" variant="h4">Training</Typography>
          <Typography color="text.secondary" variant="body2">{data.program.name}</Typography>
        </Stack>
        <Chip label={`${data.program.name} · active`} sx={{ textTransform: 'uppercase' }} />
      </Stack>

      {notice && <Alert role="status" severity="info" onClose={() => setNotice(null)}>{notice}</Alert>}

      <Paper component="section" aria-label="Week navigation" variant="outlined" sx={{ px: 1, py: 1.25 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <IconButton
            aria-label="Previous authored week"
            disabled={!previous}
            onClick={() => navigate(`/training/weeks/${previous}`)}
          >
            <ChevronLeftIcon />
          </IconButton>
          <Stack spacing={0.25} sx={{ alignItems: 'center' }} aria-live="polite">
            <Typography sx={{ color: 'text.secondary', fontSize: '0.6875rem', fontWeight: 750, letterSpacing: '0.09em', textTransform: 'uppercase' }}>
              {isCurrent ? 'Current week' : 'Training history'}
            </Typography>
            <Typography component="h2" variant="h6">Week {data.selectedWeekNumber}</Typography>
          </Stack>
          <IconButton
            aria-label="Next authored week"
            disabled={!next}
            onClick={() => navigate(`/training/weeks/${next}`)}
          >
            <ChevronRightIcon />
          </IconButton>
        </Stack>
      </Paper>

      {!isCurrent && data.currentWeekNumber && (
        <Button
          onClick={() => navigate(`/training/weeks/${data.currentWeekNumber}`)}
          variant="outlined"
          sx={{ alignSelf: 'flex-start' }}
        >
          Current · Week {data.currentWeekNumber}
        </Button>
      )}

      <Box component="section" aria-labelledby="week-workouts-heading">
        <Stack direction="row" spacing={2} sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1.5 }}>
          <Typography id="week-workouts-heading" component="h2" variant="h6">
            Week {data.selectedWeekNumber} workouts
          </Typography>
          <Typography color="text.secondary" variant="body2">
            {resolved} of {data.workouts.length} resolved
          </Typography>
        </Stack>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
          {data.workouts.map((workout) => (
            <WorkoutCard
              key={workout.weekId}
              selectedWeek={data.selectedWeekNumber}
              workout={workout}
              onLifecycle={lifecycle}
              lifecyclePending={skip.isPending || restore.isPending}
            />
          ))}
        </Box>
      </Box>

      {!data.currentWeekNumber && (
        <Alert severity="info">No next week is authored. Your training history remains available.</Alert>
      )}

      <Button component={Link} to="/training/program" variant="text" sx={{ alignSelf: 'flex-start' }}>
        Program settings
      </Button>
    </Stack>
  )
}
