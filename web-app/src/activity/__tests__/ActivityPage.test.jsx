import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ActivityPage from '@/activity/ActivityPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseExpenses = vi.fn()
vi.mock('@/activity/queries', () => ({ useExpenses: () => mockUseExpenses() }))

const expenses = [
  {
    id: 'expense-newer',
    amount: 510,
    merchant: 'FamilyMart',
    description: 'late-night snacks',
    spentAt: '2026-07-24T12:00:00Z',
    categoryName: 'Convenience Store',
  },
  {
    id: 'expense-older',
    amount: 7500,
    merchant: 'Ito Yokado',
    description: 'weekly pantry restock',
    spentAt: '2026-07-23T12:00:00Z',
    categoryName: 'Monthly Groceries',
  },
]

function loaded(data = expenses) {
  return {
    data,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }
}

describe('ActivityPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseExpenses.mockReturnValue(loaded())
  })

  it('renders the expense fields newest first', () => {
    renderWithProviders(<ActivityPage />)

    const table = screen.getByRole('table', { name: 'Household expenses' })
    const rows = within(table).getAllByRole('row')

    expect(rows[1]).toHaveTextContent('late-night snacks')
    expect(rows[1]).toHaveTextContent('¥510')
    expect(rows[1]).toHaveTextContent('Convenience Store')
    expect(rows[1]).toHaveTextContent('24 Jul 2026')
    expect(rows[2]).toHaveTextContent('weekly pantry restock')
    expect(rows[2]).toHaveTextContent('¥7,500')
    expect(table).not.toHaveTextContent('FamilyMart')
    expect(table).not.toHaveTextContent('Ito Yokado')
    expect(screen.getByText('late-night snacks')).toHaveStyle({ fontWeight: '400' })
  })

  it('filters by category and clears the filter', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ActivityPage />)

    await user.click(screen.getByRole('combobox', { name: 'Category' }))
    await user.click(screen.getByRole('option', { name: 'Monthly Groceries' }))

    expect(screen.queryByText('late-night snacks')).not.toBeInTheDocument()
    expect(screen.getByText('weekly pantry restock')).toBeInTheDocument()
    expect(screen.getByText('1 expense')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(screen.getByText('late-night snacks')).toBeInTheDocument()
    expect(screen.getByText('2 expenses')).toBeInTheDocument()
  })

  it('sorts expenses by amount', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ActivityPage />)

    await user.click(screen.getByRole('combobox', { name: 'Sort' }))
    await user.click(screen.getByRole('option', { name: 'Highest amount' }))

    const rows = within(screen.getByRole('table', { name: 'Household expenses' })).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('weekly pantry restock')
    expect(rows[2]).toHaveTextContent('late-night snacks')
  })

  it('filters expenses by an inclusive date range', () => {
    renderWithProviders(<ActivityPage />)

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-07-24' } })

    expect(screen.getByText('late-night snacks')).toBeInTheDocument()
    expect(screen.queryByText('weekly pantry restock')).not.toBeInTheDocument()
    expect(screen.getByText('1 expense')).toBeInTheDocument()
  })

  it('shows a useful empty state', () => {
    mockUseExpenses.mockReturnValue(loaded([]))

    renderWithProviders(<ActivityPage />)

    expect(screen.getByRole('heading', { name: 'No activity to show yet' })).toBeInTheDocument()
    expect(screen.getByText(/expenses confirmed through Slack/i)).toBeInTheDocument()
  })

  it('shows a retry action when loading fails', async () => {
    const user = userEvent.setup()
    const refetch = vi.fn()
    mockUseExpenses.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch,
    })

    renderWithProviders(<ActivityPage />)
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(screen.getByText('Could not load household activity.')).toBeInTheDocument()
    expect(refetch).toHaveBeenCalledOnce()
  })
})
