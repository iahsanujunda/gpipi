import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const shoppingKeys = {
  all: ['shopping-items'],
}

export function useShoppingItems() {
  return useQuery({
    queryKey: shoppingKeys.all,
    queryFn: ({ signal }) => apiFetch('/api/shopping/items', { signal }),
  })
}

function invalidatingMutation(mutationFn) {
  return function useShoppingMutation() {
    const queryClient = useQueryClient()
    return useMutation({
      mutationFn,
      onSuccess: () => queryClient.invalidateQueries({ queryKey: shoppingKeys.all }),
    })
  }
}

export const useUpdateShoppingItem = invalidatingMutation(
  ({ id, currentMutationId, item, quantity, note }) => apiFetch(`/api/shopping/items/${id}`, {
    method: 'PUT',
    body: { currentMutationId, item, quantity, note },
  }),
)

export const useRemoveShoppingItem = invalidatingMutation(
  ({ id, currentMutationId }) => apiFetch(`/api/shopping/items/${id}/remove`, {
    method: 'PUT',
    body: { currentMutationId },
  }),
)

export const useRestoreShoppingItem = invalidatingMutation(
  ({ id, currentMutationId }) => apiFetch(`/api/shopping/items/${id}/restore`, {
    method: 'PUT',
    body: { currentMutationId },
  }),
)
