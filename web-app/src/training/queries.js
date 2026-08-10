import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const trainingKeys = {
  all: ['training'],
  overview: (weekNumber) => ['training', 'overview', weekNumber ?? 'current'],
  workout: (weekNumber, workoutId) => ['training', 'workout', weekNumber, workoutId],
}

export function useTrainingOverview(weekNumber) {
  return useQuery({
    queryKey: trainingKeys.overview(weekNumber),
    queryFn: ({ signal }) => apiFetch(
      `/api/training${weekNumber ? `?week=${encodeURIComponent(weekNumber)}` : ''}`,
      { signal },
    ),
  })
}

export function useTrainingExercises() {
  return useQuery({
    queryKey: [...trainingKeys.all, 'exercises'],
    queryFn: ({ signal }) => apiFetch('/api/training/exercises', { signal }),
  })
}

export function useTrainingPrograms() {
  return useQuery({
    queryKey: [...trainingKeys.all, 'programs'],
    queryFn: ({ signal }) => apiFetch('/api/training/programs', { signal }),
  })
}

export function useActivateTrainingProgram() {
  return useTrainingMutation((programId) => apiFetch(
    `/api/training/programs/${programId}/activate`,
    { method: 'PUT' },
  ))
}

export function useWorkoutDetail(weekNumber, workoutId) {
  return useQuery({
    queryKey: trainingKeys.workout(weekNumber, workoutId),
    queryFn: ({ signal }) => apiFetch(
      `/api/training/weeks/${weekNumber}/workouts/${workoutId}`,
      { signal },
    ),
    enabled: Boolean(weekNumber && workoutId),
  })
}

function useTrainingMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: trainingKeys.all }),
  })
}

export function usePutTrainingSet() {
  return useTrainingMutation(({ weekId, prescriptionId, setNumber, set }) => apiFetch(
    `/api/training/weeks/${weekId}/prescriptions/${prescriptionId}/sets/${setNumber}`,
    { method: 'PUT', body: set },
  ))
}

export function useDeleteTrainingSet() {
  return useTrainingMutation(({ weekId, prescriptionId, setNumber }) => apiFetch(
    `/api/training/weeks/${weekId}/prescriptions/${prescriptionId}/sets/${setNumber}`,
    { method: 'DELETE' },
  ))
}

export function useUpdateTrainingSession() {
  return useTrainingMutation(({ weekId, session }) => apiFetch(
    `/api/training/weeks/${weekId}/session`,
    { method: 'PUT', body: session },
  ))
}

export function useTrainingLifecycle(action) {
  return useTrainingMutation((weekId) => apiFetch(
    `/api/training/weeks/${weekId}/${action}`,
    { method: 'PUT' },
  ))
}

export function useCreateTrainingProgram() {
  return useTrainingMutation((program) => apiFetch('/api/training/programs', {
    method: 'POST',
    body: program,
  }))
}

export function useDuplicateTrainingWeek() {
  return useTrainingMutation(({ workoutId, sourceWeek, targetWeek }) => apiFetch(
    `/api/training/workouts/${workoutId}/weeks/duplicate`,
    { method: 'POST', body: { sourceWeek, targetWeek } },
  ))
}
