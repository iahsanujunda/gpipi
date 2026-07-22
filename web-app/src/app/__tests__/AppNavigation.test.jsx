import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import AppNavigation from '@/app/AppNavigation'
import { renderWithProviders } from '@/test/renderWithProviders'

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="Current path">{location.pathname}</output>
}

describe('AppNavigation', () => {
  it('reveals only Budgets and Activity and marks the current page', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AppNavigation />, { route: '/budgets' })

    const launcher = screen.getByRole('button', { name: 'Open navigation' })
    expect(launcher).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('link', { name: 'Budgets' })).not.toBeInTheDocument()

    await user.click(launcher)

    expect(screen.getByRole('link', { name: 'Budgets' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Activity' })).not.toHaveAttribute('aria-current')
    expect(screen.getAllByRole('link')).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('navigates to Activity and closes the launcher', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <>
        <AppNavigation />
        <LocationProbe />
      </>,
      { route: '/budgets' },
    )

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.click(screen.getByRole('link', { name: 'Activity' }))

    expect(screen.getByRole('status', { name: 'Current path' })).toHaveTextContent('/activity')
    expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes on Escape and returns focus to the launcher', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AppNavigation />, { route: '/budgets' })

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.keyboard('{Escape}')

    const launcher = screen.getByRole('button', { name: 'Open navigation' })
    expect(launcher).toHaveFocus()
    expect(launcher).toHaveAttribute('aria-expanded', 'false')
  })
})
