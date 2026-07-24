import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router'
import { screen } from '@testing-library/react'
import ProtectedRoute from '@/auth/ProtectedRoute'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseAuth = vi.fn()
vi.mock('@/auth/useAuth', () => ({ useAuth: () => mockUseAuth() }))

function renderRoute() {
  return renderWithProviders(
    <Routes>
      <Route element={<ProtectedRoute />}>
        <Route index element={<div>budget app</div>} />
      </Route>
      <Route path="/access-required" element={<div>open from Slack</div>} />
    </Routes>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => vi.clearAllMocks())

  it('waits while the cookie session is checked', () => {
    mockUseAuth.mockReturnValue({ user: null, isLoading: true })
    renderRoute()
    expect(screen.getByLabelText(/checking your session/i)).toBeInTheDocument()
  })

  it('sends unauthenticated visitors to the Slack access page', () => {
    mockUseAuth.mockReturnValue({ user: null, isLoading: false })
    renderRoute()
    expect(screen.getByText(/open from Slack/i)).toBeInTheDocument()
  })

  it('renders protected content for an authenticated household member', () => {
    mockUseAuth.mockReturnValue({ user: { userId: 'U123' }, isLoading: false })
    renderRoute()
    expect(screen.getByText(/budget app/i)).toBeInTheDocument()
  })
})
