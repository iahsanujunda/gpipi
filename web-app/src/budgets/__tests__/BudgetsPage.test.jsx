import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AppNavigation from '@/app/AppNavigation'
import BudgetsPage from '@/budgets/BudgetsPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseBudgets = vi.fn()
const mockUseCreateBudget = vi.fn()
const mockUseUpdateBudget = vi.fn()
const mockUseDeactivateBudget = vi.fn()

vi.mock('@/budgets/queries', () => ({
  useBudgets: () => mockUseBudgets(),
  useCreateBudget: () => mockUseCreateBudget(),
  useUpdateBudget: () => mockUseUpdateBudget(),
  useDeactivateBudget: () => mockUseDeactivateBudget(),
}))

const eatingOut = {
  id: '00000000-0000-0000-0000-000000000001',
  name: 'Eating Out',
  description: 'Restaurants, cafes, and takeout',
  period: 'WEEKLY',
  amount: 15000,
  active: true,
  slackLoggable: true,
}

function mutation(overrides = {}) {
  return {
    isPending: false,
    mutateAsync: vi.fn(),
    reset: vi.fn(),
    ...overrides,
  }
}

function renderBudgetExperience() {
  return renderWithProviders(
    <>
      <BudgetsPage />
      <AppNavigation />
    </>,
    { route: '/budgets' },
  )
}

describe('BudgetsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBudgets.mockReturnValue({
      data: [eatingOut],
      isPending: false,
      isError: false,
    })
    mockUseCreateBudget.mockReturnValue(mutation())
    mockUseUpdateBudget.mockReturnValue(mutation())
    mockUseDeactivateBudget.mockReturnValue(mutation())
  })

  it('shows the active fields returned by Ktor without a permanent create button', () => {
    renderBudgetExperience()

    expect(screen.getByRole('heading', { name: 'Eating Out' })).toBeInTheDocument()
    expect(screen.getAllByText('WEEKLY')).not.toHaveLength(0)
    expect(screen.getByText('SLACK ON')).toBeInTheDocument()
    expect(screen.getAllByText('¥15,000')).not.toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Add budget line' })).not.toBeInTheDocument()
  })

  it('opens create from the route-aware launcher and only mutates after review', async () => {
    const user = userEvent.setup()
    const create = mutation({ mutateAsync: vi.fn().mockResolvedValue({ id: 'new-id' }) })
    mockUseCreateBudget.mockReturnValue(create)
    renderBudgetExperience()

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.click(screen.getByRole('button', { name: /add budget line/i }))

    const dialog = screen.getByRole('dialog', { name: 'New budget line' })
    await user.type(screen.getByRole('textbox', { name: /name/i }), 'Pet care')
    await user.type(screen.getByRole('textbox', { name: /description/i }), 'Vet visits and pet food')
    const amount = screen.getByRole('textbox', { name: /budget cap/i })
    await user.clear(amount)
    await user.type(amount, '12000')
    await user.click(screen.getByRole('button', { name: /review budget line/i }))

    expect(create.mutateAsync).not.toHaveBeenCalled()
    expect(screen.getByText('Confirm these details before creating the budget line.')).toBeInTheDocument()
    expect(screen.getByText('¥12,000 · MONTHLY')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Create budget line' }))

    expect(create.mutateAsync).toHaveBeenCalledWith({
      name: 'Pet care',
      description: 'Vet visits and pet food',
      amount: 12000,
      period: 'MONTHLY',
      active: true,
      slackLoggable: true,
    })
    expect(dialog).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Pet care created')
  })

  it('reviews changes before updating and preserves form values after an API error', async () => {
    const user = userEvent.setup()
    const update = mutation({
      mutateAsync: vi.fn().mockRejectedValue(new Error("A budget line named 'Groceries' already exists.")),
    })
    mockUseUpdateBudget.mockReturnValue(update)
    renderBudgetExperience()

    await user.click(screen.getByRole('button', { name: 'Edit Eating Out' }))
    const amount = screen.getByRole('textbox', { name: /budget cap/i })
    await user.clear(amount)
    await user.type(amount, '18000')
    await user.click(screen.getByRole('button', { name: 'Review changes' }))

    expect(screen.getByText('Only changed fields are shown.')).toBeInTheDocument()
    expect(screen.getAllByText('¥15,000')).not.toHaveLength(0)
    expect(screen.getByText('¥18,000')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists')
    await user.click(screen.getByRole('button', { name: 'Back to edit' }))
    expect(screen.getByRole('textbox', { name: /budget cap/i })).toHaveValue('18000')
  })

  it('requires explicit deactivation confirmation', async () => {
    const user = userEvent.setup()
    const deactivate = mutation({ mutateAsync: vi.fn().mockResolvedValue(null) })
    mockUseDeactivateBudget.mockReturnValue(deactivate)
    renderBudgetExperience()

    await user.click(screen.getByRole('button', { name: 'Edit Eating Out' }))
    await user.click(screen.getByRole('button', { name: 'Deactivate budget line' }))

    expect(screen.getByRole('heading', { name: 'Deactivate Eating Out?' })).toBeInTheDocument()
    expect(deactivate.mutateAsync).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Deactivate budget line' }))

    expect(deactivate.mutateAsync).toHaveBeenCalledWith(eatingOut.id)
    expect(screen.getByRole('status')).toHaveTextContent('Eating Out deactivated')
  })

  it('shows the unsaved confirmation and preserves edits when the user keeps editing', async () => {
    const user = userEvent.setup()
    renderBudgetExperience()

    await user.click(screen.getByRole('button', { name: 'Edit Eating Out' }))
    await user.type(screen.getByRole('textbox', { name: /name/i }), ' changed')
    await user.click(screen.getByRole('button', { name: 'Close budget editor' }))

    expect(screen.getByRole('heading', { name: 'Discard changes?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(screen.getByRole('textbox', { name: /name/i })).toHaveValue('Eating Out changed')
  })

  it('shows a recoverable error while the Ktor endpoint is unavailable', () => {
    mockUseBudgets.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    })

    renderBudgetExperience()

    expect(screen.getByText(/could not load budget lines/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })
})
