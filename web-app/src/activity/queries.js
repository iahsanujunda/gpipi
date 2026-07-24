import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const activityKeys = {
  all: ['expenses'],
}

export function useExpenses() {
  return useQuery({
    queryKey: activityKeys.all,
    queryFn: ({ signal }) => apiFetch('/api/expenses', { signal }),
  })
}
