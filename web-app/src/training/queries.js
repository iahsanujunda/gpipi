import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const trainingKeys = {
  all: ['training'],
  overview: (weekNumber) => ['training', 'overview', weekNumber ?? 'current'],
  workout: (weekNumber, workoutId) => ['training', 'workout', weekNumber, workoutId],
  google: ['training', 'google'],
  import: (importId) => ['training', 'import', importId],
}

export function useGoogleTrainingStatus() {
  return useQuery({
    queryKey: trainingKeys.google,
    queryFn: ({ signal }) => apiFetch('/api/training/google/status', { signal }),
  })
}

export function useTrainingImport(importId) {
  return useQuery({
    queryKey: trainingKeys.import(importId),
    queryFn: ({ signal }) => apiFetch(`/api/training/imports/${importId}`, { signal }),
    enabled: Boolean(importId),
  })
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

export function useCreateTrainingWorkout() {
  return useTrainingMutation(({ programId, weekNumber, workout }) => apiFetch(
    `/api/training/programs/${programId}/weeks/${weekNumber}/workouts`,
    { method: 'POST', body: workout },
  ))
}

export function useDuplicateTrainingWeek() {
  return useTrainingMutation(({ workoutId, sourceWeek, targetWeek }) => apiFetch(
    `/api/training/workouts/${workoutId}/weeks/duplicate`,
    { method: 'POST', body: { sourceWeek, targetWeek } },
  ))
}

export function useTrainingImportMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: trainingKeys.all })
      const importId = data?.id ?? data?.importId
      if (importId) queryClient.setQueryData(trainingKeys.import(importId), data)
    },
  })
}

export function useConnectGoogle(returnPath = '/training/program/import') {
  return useMutation({
    mutationFn: () => apiFetch(`/api/training/google/connect?returnPath=${encodeURIComponent(returnPath)}`),
  })
}

export function useDisconnectGoogle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch('/api/training/google/connection', { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: trainingKeys.google }),
  })
}

export function getGooglePickerToken() {
  return apiFetch('/api/training/google/picker-token')
}

export function useStartTrainingImport() {
  return useTrainingImportMutation(({ programId, spreadsheetId }) => apiFetch(
    `/api/training/programs/${programId}/imports`,
    { method: 'POST', body: { spreadsheetId } },
  ))
}

export function useChooseTrainingImportWeek() {
  return useTrainingImportMutation(({ importId, weekNumber }) => apiFetch(
    `/api/training/imports/${importId}/week`,
    { method: 'PUT', body: { weekNumber } },
  ))
}

export function useSaveTrainingImportMapping() {
  return useTrainingImportMutation(({ importId, tabs }) => apiFetch(
    `/api/training/imports/${importId}/mapping`,
    { method: 'PUT', body: { tabs } },
  ))
}

export function useExtractTrainingImport() {
  return useTrainingImportMutation((importId) => apiFetch(
    `/api/training/imports/${importId}/extract`,
    { method: 'POST' },
  ))
}

export function useSaveTrainingImportReview() {
  return useTrainingImportMutation(({ importId, workouts }) => apiFetch(
    `/api/training/imports/${importId}/review`,
    { method: 'PUT', body: { workouts } },
  ))
}

export function useApplyTrainingImport() {
  return useTrainingImportMutation((importId) => apiFetch(
    `/api/training/imports/${importId}/apply`,
    { method: 'POST' },
  ))
}
