import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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

export function useBudgetSpend(date) {
  return useQuery({
    queryKey: [...budgetKeys.all, 'spend', date],
    queryFn: ({ signal }) => apiFetch(`/api/budgets/spend?date=${encodeURIComponent(date)}`, { signal }),
  })
}

export function useCreateBudget() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (budget) => apiFetch('/api/budgets/categories', {
      method: 'POST',
      body: budget,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: budgetKeys.all }),
  })
}

export function useUpdateBudget() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, budget }) => apiFetch(`/api/budgets/categories/${id}`, {
      method: 'PUT',
      body: budget,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: budgetKeys.all }),
  })
}

export function useDeactivateBudget() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (id) => apiFetch(`/api/budgets/categories/${id}/deactivate`, {
      method: 'PUT',
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: budgetKeys.all }),
  })
}
