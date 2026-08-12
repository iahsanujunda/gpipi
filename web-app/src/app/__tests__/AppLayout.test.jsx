import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import AppLayout from '@/app/AppLayout'
import { renderWithProviders } from '@/test/renderWithProviders'

describe('AppLayout', () => {
  it('uses page content and the bottom launcher without a persistent app header', () => {
    const { container } = renderWithProviders(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/budgets" element={<h1>Budgeting</h1>} />
        </Route>
      </Routes>,
      { route: '/budgets' },
    )

    expect(screen.getByRole('heading', { name: 'Budgeting' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open navigation' })).toBeInTheDocument()
    expect(screen.queryByText('gpipi')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Return to Slack' })).not.toBeInTheDocument()
    expect(container.querySelector('header')).not.toBeInTheDocument()
  })
})
