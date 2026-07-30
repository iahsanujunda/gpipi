import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/api/http'

export const walletKeys = {
  all: ['wallets'],
  detail: (id) => ['wallets', id],
  transactions: (id) => ['wallets', id, 'transactions'],
}

export function useWallets() {
  return useQuery({
    queryKey: walletKeys.all,
    queryFn: ({ signal }) => apiFetch('/api/accounts', { signal }),
  })
}

export function useWallet(id) {
  return useQuery({
    queryKey: walletKeys.detail(id),
    queryFn: ({ signal }) => apiFetch(`/api/accounts/${id}`, { signal }),
    enabled: Boolean(id),
  })
}

export function useWalletTransactions(id) {
  return useInfiniteQuery({
    queryKey: walletKeys.transactions(id),
    queryFn: ({ pageParam, signal }) => {
      const cursor = pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''
      return apiFetch(`/api/accounts/${id}/transactions?limit=50${cursor}`, { signal })
    },
    initialPageParam: null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: Boolean(id),
  })
}

function useWalletMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: walletKeys.all }),
  })
}

export function useCreateWallet() {
  return useWalletMutation((wallet) => apiFetch('/api/accounts', {
    method: 'POST',
    body: wallet,
  }))
}

export function useUpdateWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, wallet }) => apiFetch(`/api/accounts/${id}`, {
      method: 'PUT',
      body: wallet,
    }),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: walletKeys.all })
      queryClient.invalidateQueries({ queryKey: walletKeys.detail(variables.id) })
    },
  })
}

export function useRecordMovement() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (movement) => apiFetch('/api/money-movements', {
      method: 'POST',
      body: movement,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: walletKeys.all }),
  })
}

export function invalidateMovementQueries(queryClient, accountIds) {
  queryClient.invalidateQueries({ queryKey: walletKeys.all })
  accountIds.forEach((id) => {
    queryClient.invalidateQueries({ queryKey: walletKeys.detail(id) })
    queryClient.invalidateQueries({ queryKey: walletKeys.transactions(id) })
  })
}
