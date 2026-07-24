import { afterEach, describe, expect, it, vi } from 'vitest'
import { redeem } from '@/auth/authApi'

describe('magic-link redemption', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shares one request when React Strict Mode starts the same redemption twice', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue({ userId: 'U123', expiresAt: '2026-07-22T13:00:00Z' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const [first, second] = await Promise.all([
      redeem('single-use-nonce'),
      redeem('single-use-nonce'),
    ])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(first).toEqual(second)
  })
})
