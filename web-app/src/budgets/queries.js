import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const budgetKeys = {
  all: ['budgets'],
}

export function useBudgets() {
  return useQuery({
    queryKey: budgetKeys.all,
    queryFn: ({ signal }) => apiFetch('/api/budgets', { signal }),
  })
}
