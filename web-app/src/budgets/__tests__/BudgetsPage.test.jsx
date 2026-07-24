import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AppNavigation from '@/app/AppNavigation'
import BudgetsPage from '@/budgets/BudgetsPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseBudgets = vi.fn()
const mockUseBudgetSpend = vi.fn()
const mockUseCreateBudget = vi.fn()
const mockUseUpdateBudget = vi.fn()
const mockUseDeactivateBudget = vi.fn()

vi.mock('@/budgets/queries', () => ({
  useBudgets: () => mockUseBudgets(),
  useBudgetSpend: (date) => mockUseBudgetSpend(date),
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

const groceries = {
  id: '00000000-0000-0000-0000-000000000002',
  name: 'Monthly Groceries',
  description: 'Supermarket and pantry spending',
  period: 'MONTHLY',
  amount: 75000,
  active: true,
  slackLoggable: true,
}

const transport = {
  id: '00000000-0000-0000-0000-000000000003',
  name: 'Transport',
  description: 'Trains, buses, taxis, and IC top-ups',
  period: 'MONTHLY',
  amount: 20000,
  active: true,
  slackLoggable: true,
}

const homeRepairs = {
  id: '00000000-0000-0000-0000-000000000004',
  name: 'Home repairs',
  description: 'Unplanned household maintenance',
  period: 'MONTHLY',
  amount: 0,
  active: true,
  slackLoggable: false,
}

function spendRow(budget, spent) {
  const window = budget.period === 'WEEKLY'
    ? { windowStart: '2026-07-20', windowEndExclusive: '2026-07-27' }
    : { windowStart: '2026-07-01', windowEndExclusive: '2026-08-01' }
  return {
    categoryId: budget.id,
    name: budget.name,
    period: budget.period,
    ...window,
    cap: budget.amount,
    spent,
    remaining: budget.amount - spent,
  }
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
    mockUseBudgetSpend.mockReturnValue({
      data: [spendRow(eatingOut, 12000)],
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
    expect(screen.getAllByText('WEEKLY · 20–26 JUL')).not.toHaveLength(0)
    expect(screen.getByText('SLACK ON')).toBeInTheDocument()
    expect(screen.getAllByText(/Cap ¥15,000/)).not.toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Add budget line' })).not.toBeInTheDocument()
  })

  it('shows exact spend and difference while progress remains a supporting signal', () => {
    mockUseBudgets.mockReturnValue({
      data: [eatingOut, groceries],
      isPending: false,
      isError: false,
    })
    mockUseBudgetSpend.mockReturnValue({
      data: [
        spendRow(eatingOut, 12000),
        spendRow(groceries, 46200),
      ],
      isPending: false,
      isError: false,
    })

    renderBudgetExperience()

    expect(screen.getAllByText('¥12,000')).not.toHaveLength(0)
    expect(screen.getAllByText('¥3,000 left')).not.toHaveLength(0)
    expect(screen.getAllByText('¥28,800 left')).not.toHaveLength(0)
    expect(screen.getAllByRole('progressbar', { name: 'Eating Out utilization' })[0])
      .toHaveAttribute('aria-valuenow', '80')
    expect(screen.getAllByRole('progressbar', { name: 'Monthly Groceries utilization' })[0])
      .toHaveAttribute('aria-valuetext', '62% used; ¥46,200 spent of ¥75,000')
  })

  it('shows the real percentage when over cap and omits utilization when the cap is zero', () => {
    mockUseBudgets.mockReturnValue({
      data: [transport, homeRepairs],
      isPending: false,
      isError: false,
    })
    mockUseBudgetSpend.mockReturnValue({
      data: [
        spendRow(transport, 22000),
        spendRow(homeRepairs, 2000),
      ],
      isPending: false,
      isError: false,
    })

    renderBudgetExperience()

    expect(screen.getAllByText('¥2,000 over')).toHaveLength(2)
    expect(screen.getAllByText('110%')).not.toHaveLength(0)
    expect(screen.getAllByRole('progressbar', { name: 'Transport utilization' })[0])
      .toHaveAttribute('aria-valuenow', '100')
    expect(screen.getAllByRole('progressbar', { name: 'Transport utilization' })[0])
      .toHaveAttribute('aria-valuetext', '110% used; ¥22,000 spent of ¥20,000')
    expect(screen.getAllByText('No cap set')).not.toHaveLength(0)
    expect(screen.queryByRole('progressbar', { name: 'Home repairs utilization' }))
      .not.toBeInTheDocument()
    expect(screen.getByText('2 lines · 1 over cap')).toBeInTheDocument()
  })

  it('keeps budget details editable while spending is still loading', () => {
    mockUseBudgetSpend.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
    })

    renderBudgetExperience()

    expect(screen.getByRole('heading', { name: 'Eating Out' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit Eating Out' })).toBeInTheDocument()
    expect(screen.getAllByRole('status', { name: 'Loading spending for Eating Out' }))
      .not.toHaveLength(0)
  })

  it('keeps budget details editable and retries an unavailable spend projection', async () => {
    const user = userEvent.setup()
    const refetch = vi.fn()
    mockUseBudgetSpend.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch,
    })

    renderBudgetExperience()

    expect(screen.getByRole('heading', { name: 'Eating Out' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit Eating Out' })).toBeInTheDocument()
    expect(screen.getAllByText('Spending unavailable')).not.toHaveLength(0)
    const retry = screen.getAllByRole('button', { name: 'Retry spending' })[0]
    expect(getComputedStyle(retry).minHeight).toBe('44px')
    await user.click(retry)
    expect(refetch).toHaveBeenCalledOnce()
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
