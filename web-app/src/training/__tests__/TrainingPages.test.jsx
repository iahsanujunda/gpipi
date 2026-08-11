import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import TrainingPage from '@/training/TrainingPage'
import WorkoutPage from '@/training/WorkoutPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseTrainingOverview = vi.fn()
const mockUseWorkoutDetail = vi.fn()
const mockPutSet = vi.fn()
const mockDeleteSet = vi.fn()
const mockUpdateSession = vi.fn()
const mockLifecycle = vi.fn()

vi.mock('@/training/queries', () => ({
  useTrainingOverview: (week) => mockUseTrainingOverview(week),
  useWorkoutDetail: (week, workoutId) => mockUseWorkoutDetail(week, workoutId),
  usePutTrainingSet: () => mockPutSet(),
  useDeleteTrainingSet: () => mockDeleteSet(),
  useUpdateTrainingSession: () => mockUpdateSession(),
  useTrainingLifecycle: (action) => mockLifecycle(action),
}))

function mutation(overrides = {}) {
  return { isPending: false, mutateAsync: vi.fn().mockResolvedValue(null), ...overrides }
}

const program = {
  id: 'program-1',
  name: 'M1',
  note: null,
  startsOn: '2026-08-01',
  active: true,
}

const workout = {
  weekId: 'week-2-a',
  workoutId: 'workout-a',
  workoutName: 'Full Body 1',
  status: 'COMPLETED',
  sessionId: 'session-a',
  performedOn: '2026-08-02',
  setCount: 6,
  updatedAt: '2026-08-02T04:00:00Z',
}

const exercise = {
  prescriptionId: 'prescription-1',
  performedExerciseId: 'performed-1',
  position: 1,
  exerciseName: 'Split squat',
  demoUrl: null,
  executionType: 'REPS_PER_SIDE',
  targetSets: '3 each',
  targetRest: '60 sec',
  targetReps: '10 / side',
  targetLoad: '8 kg each',
  targetRir: '3',
  targetTempo: null,
  targetNote: null,
  executionNote: null,
  sets: [
    { id: 'set-2', setNumber: 2, reps: 10, durationSeconds: null, load: '8', rir: 2, note: null },
    { id: 'set-3', setNumber: 3, reps: 9, durationSeconds: null, load: '8', rir: 1, note: null },
  ],
}

describe('training iteration 1 pages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockLifecycle.mockReturnValue(mutation())
    mockPutSet.mockReturnValue(mutation())
    mockDeleteSet.mockReturnValue(mutation())
    mockUpdateSession.mockReturnValue(mutation())
  })

  it('shows a selected historical week with a one-tap return to current', () => {
    mockUseTrainingOverview.mockReturnValue({
      data: {
        program,
        currentWeekNumber: 3,
        selectedWeekNumber: 2,
        availableWeekNumbers: [1, 2, 3],
        workouts: [workout],
      },
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/weeks/:weekNumber" element={<TrainingPage />} /></Routes>,
      { route: '/training/weeks/2' },
    )

    expect(screen.getByRole('heading', { name: 'Week 2' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'M1' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Edit M1 program' })).toHaveAttribute('href', '/training/program')
    expect(screen.queryByText('Program settings')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Current · Week 3' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute(
      'href',
      '/training/weeks/2/workouts/workout-a',
    )
    expect(screen.queryByRole('button', { name: 'Add workout' })).not.toBeInTheDocument()
  })

  it('offers manual or Sheet authoring only from the current week', async () => {
    const user = userEvent.setup()
    mockUseTrainingOverview.mockReturnValue({
      data: {
        program,
        currentWeekNumber: 1,
        selectedWeekNumber: 1,
        availableWeekNumbers: [1],
        workouts: [],
      },
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/weeks/:weekNumber" element={<TrainingPage />} /></Routes>,
      { route: '/training/weeks/1' },
    )

    expect(screen.getByText('No workouts yet')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add workout' }))
    expect(await screen.findByRole('dialog', { name: 'Add workout' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create manually' })).toHaveAttribute(
      'href',
      '/training/weeks/1/workouts/new',
    )
    expect(screen.getByRole('link', { name: 'Import from Google Sheet' })).toHaveAttribute(
      'href',
      '/training/program/import',
    )
  })

  it('keeps execution blank and selects the lowest missing stable set slot', async () => {
    const user = userEvent.setup()
    const put = mutation()
    mockPutSet.mockReturnValue(put)
    mockUseWorkoutDetail.mockReturnValue({
      data: {
        program,
        currentWeekNumber: 3,
        weekId: 'week-3-a',
        weekNumber: 3,
        skipped: false,
        workoutId: 'workout-a',
        workoutName: 'Full Body 1',
        workoutNote: null,
        session: {
          id: 'session-a',
          performedOn: '2026-08-08',
          status: 'IN_PROGRESS',
          note: null,
          updatedAt: '2026-08-08T03:00:00Z',
          completedAt: null,
        },
        groups: [{ position: 1, label: 'A', kind: 'STRAIGHT_SET', exercises: [exercise] }],
      },
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/weeks/:weekNumber/workouts/:workoutId" element={<WorkoutPage />} /></Routes>,
      { route: '/training/weeks/3/workouts/workout-a' },
    )

    const editor = screen.getByRole('form', { name: 'Set editor for Split squat' })
    expect(editor).toHaveFormValues({ primary: null, load: null, rir: null, note: '' })
    expect(screen.getByRole('button', { name: 'Edit set 2 for Split squat' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit set 3 for Split squat' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Correct Set 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log new Set 4' })).toBeInTheDocument()

    await user.type(screen.getByLabelText('Reps / side'), '11')
    await user.click(screen.getByRole('button', { name: 'Log Set 1' }))

    expect(put.mutateAsync).toHaveBeenCalledWith({
      weekId: 'week-3-a',
      prescriptionId: 'prescription-1',
      setNumber: 1,
      set: {
        reps: 11,
        durationSeconds: null,
        load: null,
        rir: null,
        note: null,
      },
    })
  })
})
