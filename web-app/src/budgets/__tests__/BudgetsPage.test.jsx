import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import BudgetsPage from '@/budgets/BudgetsPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseBudgets = vi.fn()
vi.mock('@/budgets/queries', () => ({ useBudgets: () => mockUseBudgets() }))

describe('BudgetsPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the budget fields returned by Ktor', () => {
    mockUseBudgets.mockReturnValue({
      data: [{
        id: 'category-1',
        name: 'Eating Out',
        description: 'Restaurants, cafes, and takeout',
        period: 'WEEKLY',
        amount: 15000,
      }],
      isPending: false,
      isError: false,
    })

    renderWithProviders(<BudgetsPage />)

    expect(screen.getByRole('heading', { name: 'Eating Out' })).toBeInTheDocument()
    expect(screen.getByText('WEEKLY')).toBeInTheDocument()
    expect(screen.getByText('¥15,000')).toBeInTheDocument()
  })

  it('shows a recoverable error while the Ktor endpoint is unavailable', () => {
    mockUseBudgets.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    })

    renderWithProviders(<BudgetsPage />)

    expect(screen.getByText(/could not load budget lines/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })
})
