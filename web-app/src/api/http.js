import { notifyAuthExpired } from '@/auth/authState'

const BASE = import.meta.env.VITE_API_URL ?? ''

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function errorMessage(response) {
  const body = await response.json().catch(() => null)
  return body?.detail ?? body?.message ?? `Request failed (${response.status})`
}

export async function apiFetch(path, options = {}) {
  const { allowUnauthorized = false, body, headers, ...requestOptions } = options
  const response = await fetch(`${BASE}${path}`, {
    ...requestOptions,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    body: body === undefined || typeof body === 'string' ? body : JSON.stringify(body),
  })

  if (response.status === 401 && !allowUnauthorized) notifyAuthExpired()
  if (!response.ok) throw new ApiError(await errorMessage(response), response.status)
  if (response.status === 204) return null
  return response.json()
}
