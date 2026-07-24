import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router'
import { useMemo } from 'react'
import { describe, expect, it, vi } from 'vitest'
import AppNavigation from '@/app/AppNavigation'
import { AddIcon } from '@/app/AppIcons'
import { usePageAction } from '@/app/pageActions'
import { renderWithProviders } from '@/test/renderWithProviders'

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="Current path">{location.pathname}</output>
}

function NavigationWithBudgetAction({ onSelect }) {
  const action = useMemo(() => ({
    id: 'add-budget-line',
    label: 'Add budget line',
    icon: AddIcon,
    onSelect,
  }), [onSelect])
  usePageAction(action)
  return <AppNavigation />
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

  it('separates a route-provided page action from navigation and animates the launcher icons', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const { container } = renderWithProviders(
      <NavigationWithBudgetAction onSelect={onSelect} />,
      { route: '/budgets' },
    )

    const launcher = screen.getByRole('button', { name: 'Open navigation' })
    expect(launcher).toHaveAttribute('data-launcher-state', 'closed')
    expect(container.querySelector('[data-launcher-icon="brand"]')).toHaveStyle({ opacity: '1' })
    expect(container.querySelector('[data-launcher-icon="close"]')).toHaveStyle({ opacity: '0' })

    await user.click(launcher)

    const pageAction = screen.getByRole('button', { name: /add budget line/i })
    expect(pageAction).toHaveAttribute('data-menu-entry', 'page-action')
    expect(pageAction.closest('section')).not.toBe(screen.getByRole('navigation', { name: 'Primary' }))
    expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveAttribute('data-launcher-state', 'open')
    expect(container.querySelector('[data-launcher-icon="brand"]')).toHaveStyle({ opacity: '0' })
    expect(container.querySelector('[data-launcher-icon="close"]')).toHaveStyle({ opacity: '1' })

    await user.click(pageAction)

    expect(onSelect).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveAttribute('aria-expanded', 'false')
  })
})
