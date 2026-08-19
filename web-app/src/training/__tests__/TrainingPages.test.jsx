import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
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
  useTrainingWriteStatus: () => ({ data: { state: 'NOT_WRITTEN' }, isPending: false }),
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
    expect(screen.getByRole('link', { name: 'Edit M1 program' })).toHaveAttribute('href', '/training/program/program-1')
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

  it('offers the same top Add workout action for the week after a resolved plan', async () => {
    const user = userEvent.setup()
    mockUseTrainingOverview.mockReturnValue({
      data: {
        program,
        currentWeekNumber: null,
        selectedWeekNumber: 4,
        availableWeekNumbers: [1, 2, 3, 4],
        workouts: [workout],
      },
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/weeks/:weekNumber" element={<TrainingPage />} /></Routes>,
      { route: '/training/weeks/4' },
    )

    await user.click(screen.getByRole('button', { name: 'Add workout' }))
    expect(await screen.findByRole('dialog', { name: 'Add workout' })).toBeInTheDocument()
    expect(screen.getByText('Week 5')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create manually' })).toHaveAttribute(
      'href',
      '/training/weeks/5/workouts/new',
    )
    expect(screen.getByRole('link', { name: 'Import from Google Sheet' })).toHaveAttribute(
      'href',
      '/training/program/import',
    )
    expect(screen.queryByText(/No next week is authored/)).not.toBeInTheDocument()
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

  it('renders labelled prescriptions, verbatim cues, and compact demo fallbacks', () => {
    const youtubeExercise = {
      ...exercise,
      prescriptionId: 'prescription-youtube',
      exerciseName: 'Goblet squat',
      demoUrl: 'https://www.youtube.com/shorts/jO2Jl9eZpXk',
      executionType: 'REPS',
      targetSets: '3',
      targetReps: '12',
      targetLoad: '15 kg dumbbell\n1 pc',
      targetRest: '45–60 sec',
      targetRir: null,
      targetTempo: null,
      targetNote: 'Set-up:\n- Keep the whole foot planted\n\nDuring the rep:\n- Control the descent',
      sets: [],
    }
    const fallbackExercise = {
      ...exercise,
      prescriptionId: 'prescription-fallback',
      exerciseName: 'DB Romanian deadlift',
      demoUrl: 'https://trainer.example/rdl-demo',
      targetRir: null,
      targetTempo: null,
      targetNote: null,
      sets: [],
    }
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
        session: null,
        groups: [{
          position: 1,
          label: 'A',
          kind: 'STRAIGHT_SET',
          exercises: [youtubeExercise, fallbackExercise],
        }],
      },
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/weeks/:weekNumber/workouts/:workoutId" element={<WorkoutPage />} /></Routes>,
      { route: '/training/weeks/3/workouts/workout-a' },
    )

    const thumbnail = screen.getByRole('img', { name: 'Video thumbnail for Goblet squat' })
    expect(thumbnail).toHaveAttribute('src', 'https://i.ytimg.com/vi/jO2Jl9eZpXk/hqdefault.jpg')
    expect(screen.getByRole('link', { name: 'Open demo video for Goblet squat' })).toHaveAttribute(
      'href',
      'https://www.youtube.com/shorts/jO2Jl9eZpXk',
    )

    const prescription = screen.getByRole('region', { name: 'Prescription for Goblet squat' })
    expect(within(prescription).getByText('Sets')).toBeInTheDocument()
    expect(within(prescription).getByText('3')).toBeInTheDocument()
    expect(within(prescription).getByText('Reps')).toBeInTheDocument()
    expect(within(prescription).getByText('12')).toBeInTheDocument()
    expect(within(prescription).getByText((_, element) => element.textContent === '15 kg dumbbell\n1 pc')).toHaveStyle({ whiteSpace: 'pre-wrap' })
    expect(within(prescription).queryByText('RIR')).not.toBeInTheDocument()
    expect(within(prescription).queryByText('Tempo')).not.toBeInTheDocument()
    expect(screen.queryByText(/3 sets · 12/)).not.toBeInTheDocument()

    expect(within(prescription).getByText((_, element) => (
      element.children.length === 0 && element.textContent === youtubeExercise.targetNote
    ))).toHaveStyle({ whiteSpace: 'pre-wrap' })
    const fallback = screen.getByRole('link', { name: 'Open demo video for DB Romanian deadlift' })
    expect(within(fallback).getByText('Demo video')).toBeInTheDocument()
    expect(screen.queryByText(/Preview unavailable/i)).not.toBeInTheDocument()

    fireEvent.error(thumbnail)
    expect(screen.queryByRole('img', { name: 'Video thumbnail for Goblet squat' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('link', { name: 'Open demo video for Goblet squat' })).getByText('Demo video')).toBeInTheDocument()
  })
})
