import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMediaQuery } from '@mui/material'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ActivityPage from '@/activity/ActivityPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseExpenses = vi.fn()
vi.mock('@/activity/queries', () => ({ useExpenses: () => mockUseExpenses() }))

vi.mock('@mui/material', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useMediaQuery: vi.fn(() => false) }
})

function mobileQuery(query) {
  return !String(query).includes('prefers-reduced-motion')
}

describe('Activity mobile drawers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useMediaQuery).mockImplementation(mobileQuery)
    mockUseExpenses.mockReturnValue({
      data: [
        {
          id: 'expense-1',
          amount: 7500,
          merchant: 'Ito Yokado',
          spentAt: '2026-07-24T12:00:00Z',
          categoryName: 'Monthly Groceries',
        },
      ],
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    })
  })

  it('filters Activity through the category option sheet', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ActivityPage />)

    const trigger = screen.getByRole('combobox', { name: 'Category' })
    await user.click(trigger)

    expect(screen.getByRole('dialog', { name: 'Category' })).toHaveAttribute(
      'data-presentation',
      'bottom-sheet-options',
    )
    await user.click(screen.getByRole('option', { name: 'Monthly Groceries' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Category' })).not.toBeInTheDocument())
    expect(trigger).toHaveValue('Monthly Groceries')
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('opens both date filters as calendar sheets', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ActivityPage />)

    const from = screen.getByRole('combobox', { name: 'From' })
    await user.click(from)
    expect(screen.getByRole('dialog', { name: 'From' })).toHaveAttribute(
      'data-presentation',
      'bottom-sheet-date-picker',
    )

    await user.click(screen.getByRole('button', { name: 'Close From' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'From' })).not.toBeInTheDocument())

    const to = screen.getByRole('combobox', { name: 'To' })
    await user.click(to)
    expect(screen.getByRole('dialog', { name: 'To' })).toHaveAttribute(
      'data-presentation',
      'bottom-sheet-date-picker',
    )
  })
})
