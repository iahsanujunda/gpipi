import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const trainingKeys = {
  all: ['training'],
  overview: (weekNumber) => ['training', 'overview', weekNumber ?? 'current'],
  workout: (weekNumber, workoutId) => ['training', 'workout', weekNumber, workoutId],
  google: ['training', 'google'],
  googleSheets: (query) => ['training', 'google', 'sheets', query],
  import: (importId) => ['training', 'import', importId],
  write: (writeId) => ['training', 'write', writeId],
  writeDestination: (sessionId) => ['training', 'write-destination', sessionId],
  writeStatus: (sessionId) => ['training', 'write-status', sessionId],
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

export function useTrainingWriteDestination(sessionId) {
  return useQuery({
    queryKey: trainingKeys.writeDestination(sessionId),
    queryFn: ({ signal }) => apiFetch(`/api/training/sessions/${sessionId}/write-destination`, { signal }),
    enabled: Boolean(sessionId),
  })
}

export function useTrainingWriteStatus(sessionId) {
  return useQuery({
    queryKey: trainingKeys.writeStatus(sessionId),
    queryFn: ({ signal }) => apiFetch(`/api/training/sessions/${sessionId}/write-status`, { signal }),
    enabled: Boolean(sessionId),
  })
}

export function useTrainingWrite(writeId) {
  return useQuery({
    queryKey: trainingKeys.write(writeId),
    queryFn: ({ signal }) => apiFetch(`/api/training/writes/${writeId}`, { signal }),
    enabled: Boolean(writeId),
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
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (program) => apiFetch('/api/training/programs', {
      method: 'POST',
      body: program,
    }),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: [...trainingKeys.all, 'overview'] })
      await queryClient.invalidateQueries({ queryKey: [...trainingKeys.all, 'programs'] })
    },
  })
}

export function useUpdateTrainingProgram() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ programId, program }) => apiFetch(`/api/training/programs/${programId}`, {
      method: 'PUT',
      body: program,
    }),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: [...trainingKeys.all, 'overview'] })
      await queryClient.invalidateQueries({ queryKey: [...trainingKeys.all, 'programs'] })
    },
  })
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

export function useGoogleSheets(query, enabled = true) {
  return useInfiniteQuery({
    queryKey: trainingKeys.googleSheets(query),
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams()
      if (query) params.set('query', query)
      if (pageParam) params.set('pageToken', pageParam)
      const suffix = params.size ? `?${params}` : ''
      return apiFetch(`/api/training/google/sheets${suffix}`, { signal })
    },
    initialPageParam: null,
    getNextPageParam: (lastPage) => lastPage.nextPageToken || undefined,
    enabled,
  })
}

export function useStartTrainingImport() {
  return useTrainingImportMutation(({ programId, selectionToken }) => apiFetch(
    `/api/training/programs/${programId}/imports`,
    { method: 'POST', body: { selectionToken } },
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

function useTrainingWriteMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (data) => {
      if (data?.id) queryClient.setQueryData(trainingKeys.write(data.id), data)
      queryClient.invalidateQueries({ queryKey: trainingKeys.all })
    },
  })
}

export function useStartTrainingWrite() {
  return useTrainingWriteMutation(({ sessionId, selectionToken = null }) => apiFetch(
    `/api/training/sessions/${sessionId}/writes`,
    { method: 'POST', body: { selectionToken } },
  ))
}

export function useChooseTrainingWriteWeek() {
  return useTrainingWriteMutation(({ writeId, weekNumber }) => apiFetch(
    `/api/training/writes/${writeId}/week`,
    { method: 'PUT', body: { weekNumber } },
  ))
}

export function useConfirmTrainingWriteMatches() {
  return useTrainingWriteMutation(({ writeId, tabKey, movements }) => apiFetch(
    `/api/training/writes/${writeId}/matches`,
    { method: 'PUT', body: { tabKey, movements } },
  ))
}

export function usePrepareTrainingWrite() {
  return useTrainingWriteMutation((writeId) => apiFetch(
    `/api/training/writes/${writeId}/preview`,
    { method: 'POST' },
  ))
}

export function useConfirmTrainingWrite() {
  return useTrainingWriteMutation((writeId) => apiFetch(
    `/api/training/writes/${writeId}/confirm`,
    { method: 'POST' },
  ))
}

export function useVerifyTrainingWrite() {
  return useTrainingWriteMutation((writeId) => apiFetch(
    `/api/training/writes/${writeId}/verify`,
    { method: 'POST' },
  ))
}
