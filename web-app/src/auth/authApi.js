import { apiFetch } from '@/api/http'

const redemptions = new Map()

export function getSession() {
  return apiFetch('/api/auth/session', {
    allowUnauthorized: true,
  }).catch((error) => {
    if (error.status === 401) return null
    throw error
  })
}

export function redeem(nonce) {
  if (!redemptions.has(nonce)) {
    redemptions.set(
      nonce,
      apiFetch('/api/auth/redeem', {
        method: 'POST',
        body: { nonce },
        allowUnauthorized: true,
      }),
    )
  }
  return redemptions.get(nonce)
}

export function logout() {
  return apiFetch('/api/auth/logout', { method: 'POST' })
}
