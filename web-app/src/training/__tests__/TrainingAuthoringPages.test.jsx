import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import TrainingProgramPage from '@/training/TrainingProgramPage'
import TrainingWorkoutAuthoringPage from '@/training/TrainingWorkoutAuthoringPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockCreateProgram = vi.fn()
const mockUpdateProgram = vi.fn()
const mockCreateWorkout = vi.fn()
const mockOverview = vi.fn()
const mockExercises = vi.fn()
const mockPrograms = vi.fn()
const mockActivate = vi.fn()

vi.mock('@/training/queries', () => ({
  useCreateTrainingProgram: () => mockCreateProgram(),
  useUpdateTrainingProgram: () => mockUpdateProgram(),
  useCreateTrainingWorkout: () => mockCreateWorkout(),
  useTrainingOverview: (week) => mockOverview(week),
  useTrainingExercises: () => mockExercises(),
  useTrainingPrograms: () => mockPrograms(),
  useActivateTrainingProgram: () => mockActivate(),
}))

function mutation() {
  return { isPending: false, mutateAsync: vi.fn().mockResolvedValue({ id: 'created' }) }
}

describe('training manual authoring flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreateProgram.mockReturnValue(mutation())
    mockUpdateProgram.mockReturnValue(mutation())
    mockCreateWorkout.mockReturnValue(mutation())
    mockOverview.mockReturnValue({ data: null, isPending: false, isError: false })
    mockExercises.mockReturnValue({ data: [], isPending: false, isError: false })
    mockPrograms.mockReturnValue({ data: [], isPending: false, isError: false })
    mockActivate.mockReturnValue(mutation())
  })

  it('creates only program details without a workout or import branch', async () => {
    const user = userEvent.setup()
    const create = mutation()
    mockCreateProgram.mockReturnValue(create)

    renderWithProviders(
      <Routes><Route path="training/program" element={<TrainingProgramPage />} /></Routes>,
      { route: '/training/program' },
    )

    expect(screen.getByRole('heading', { name: 'Create Program' })).toBeInTheDocument()
    expect(screen.queryByText('Import from Google Sheet')).not.toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: /Program name/ }), 'M2')
    await user.type(screen.getByRole('textbox', { name: 'Program note (optional)' }), 'Pregnancy strength block')
    await user.click(screen.getByRole('button', { name: 'Create Program' }))

    expect(create.mutateAsync).toHaveBeenCalledWith({
      name: 'M2',
      startsOn: null,
      note: 'Pregnancy strength block',
    })
  })

  it('loads an existing program into the edit form and updates only its details', async () => {
    const user = userEvent.setup()
    const update = mutation()
    mockUpdateProgram.mockReturnValue(update)
    mockPrograms.mockReturnValue({
      data: [{
        id: 'program-1',
        name: 'M1',
        startsOn: '2026-08-01',
        note: 'Strength block',
        active: true,
      }],
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes><Route path="training/program/:programId" element={<TrainingProgramPage />} /></Routes>,
      { route: '/training/program/program-1' },
    )

    expect(screen.getByRole('heading', { name: 'Edit Program' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Program name/ })).toHaveValue('M1')
    expect(screen.getByLabelText('Start date (optional)')).toHaveValue('2026-08-01')
    expect(screen.getByRole('textbox', { name: 'Program note (optional)' })).toHaveValue('Strength block')

    await user.clear(screen.getByRole('textbox', { name: /Program name/ }))
    await user.type(screen.getByRole('textbox', { name: /Program name/ }), 'M1 updated')
    await user.click(screen.getByRole('button', { name: 'Save Program' }))

    expect(update.mutateAsync).toHaveBeenCalledWith({
      programId: 'program-1',
      program: {
        name: 'M1 updated',
        startsOn: '2026-08-01',
        note: 'Strength block',
      },
    })
    expect(screen.queryByRole('heading', { name: 'Saved programs' })).not.toBeInTheDocument()
  })

  it('adds one reviewed workout to the routed current week', async () => {
    const user = userEvent.setup()
    const create = mutation()
    mockCreateWorkout.mockReturnValue(create)
    mockOverview.mockReturnValue({
      data: {
        program: { id: 'program-1', name: 'M2', active: true },
        currentWeekNumber: 1,
        selectedWeekNumber: 1,
        availableWeekNumbers: [1],
        workouts: [],
      },
      isPending: false,
      isError: false,
    })
    mockExercises.mockReturnValue({
      data: [{ id: 'exercise-1', name: 'Goblet squat', demoUrl: null }],
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes>
        <Route path="training/weeks/:weekNumber/workouts/new" element={<TrainingWorkoutAuthoringPage />} />
      </Routes>,
      { route: '/training/weeks/1/workouts/new' },
    )

    await user.type(screen.getByRole('textbox', { name: /Workout name/ }), 'Full Body 1')
    await user.click(screen.getByRole('combobox', { name: /Exercise — select or create/ }))
    await user.click(screen.getByRole('option', { name: 'Goblet squat' }))
    await user.click(screen.getByRole('combobox', { name: /Execution type — confirm/ }))
    await user.click(screen.getByRole('option', { name: 'Reps', exact: true }))
    await user.type(screen.getByLabelText('Sets'), '3')
    await user.type(screen.getByLabelText('Reps / time'), '10–12')
    await user.click(screen.getAllByRole('button', { name: 'Save Workout' })[0])

    expect(create.mutateAsync).toHaveBeenCalledWith({
      programId: 'program-1',
      weekNumber: 1,
      workout: expect.objectContaining({
        name: 'Full Body 1',
        groups: [expect.objectContaining({
          label: 'A',
          kind: 'STRAIGHT_SET',
          prescriptions: [expect.objectContaining({
            exerciseId: 'exercise-1',
            exerciseName: 'Goblet squat',
            createExercise: false,
            executionType: 'REPS',
            sets: '3',
            reps: '10–12',
          })],
        })],
      }),
    })
  })

  it('adds the first workout to the next week after the plan is resolved', async () => {
    const user = userEvent.setup()
    const create = mutation()
    mockCreateWorkout.mockReturnValue(create)
    mockOverview.mockReturnValue({
      data: {
        program: { id: 'program-1', name: 'M2', active: true },
        currentWeekNumber: null,
        selectedWeekNumber: 4,
        availableWeekNumbers: [1, 2, 3, 4],
        workouts: [],
      },
      isPending: false,
      isError: false,
    })
    mockExercises.mockReturnValue({
      data: [{ id: 'exercise-1', name: 'Goblet squat', demoUrl: null }],
      isPending: false,
      isError: false,
    })

    renderWithProviders(
      <Routes>
        <Route path="training/weeks/:weekNumber/workouts/new" element={<TrainingWorkoutAuthoringPage />} />
      </Routes>,
      { route: '/training/weeks/5/workouts/new' },
    )

    expect(screen.getByText('Week 5')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Training' })).toHaveAttribute('href', '/training/weeks/4')
    await user.type(screen.getByRole('textbox', { name: /Workout name/ }), 'Full Body 3')
    await user.click(screen.getByRole('combobox', { name: /Exercise — select or create/ }))
    await user.click(screen.getByRole('option', { name: 'Goblet squat' }))
    await user.click(screen.getByRole('combobox', { name: /Execution type — confirm/ }))
    await user.click(screen.getByRole('option', { name: 'Reps', exact: true }))
    await user.click(screen.getAllByRole('button', { name: 'Save Workout' })[0])

    expect(create.mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      programId: 'program-1',
      weekNumber: 5,
    }))
  })
})
