import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ShoppingPage from '@/shopping/ShoppingPage'
import { renderWithProviders } from '@/test/renderWithProviders'

const mockUseShoppingItems = vi.fn()
const mockUseUpdateShoppingItem = vi.fn()
const mockUseRemoveShoppingItem = vi.fn()
const mockUseRestoreShoppingItem = vi.fn()

vi.mock('@/shopping/queries', () => ({
  useShoppingItems: () => mockUseShoppingItems(),
  useUpdateShoppingItem: () => mockUseUpdateShoppingItem(),
  useRemoveShoppingItem: () => mockUseRemoveShoppingItem(),
  useRestoreShoppingItem: () => mockUseRestoreShoppingItem(),
}))

const milk = {
  id: '00000000-0000-0000-0000-000000000001',
  item: 'Milk',
  quantity: '1 carton',
  note: 'low fat',
  status: 'PENDING',
  addedBy: 'U-JUNDA',
  addedAt: '2026-07-27T08:24:00+09:00',
  boughtBy: null,
  boughtAt: null,
  removedBy: null,
  removedAt: null,
  currentMutationId: '10000000-0000-0000-0000-000000000001',
}

const eggs = {
  ...milk,
  id: '00000000-0000-0000-0000-000000000002',
  item: 'Eggs',
  quantity: null,
  note: null,
  status: 'BOUGHT',
  boughtBy: 'U-WULAN',
  boughtAt: '2026-07-26T18:10:00+09:00',
  currentMutationId: '10000000-0000-0000-0000-000000000002',
}

const soap = {
  ...milk,
  id: '00000000-0000-0000-0000-000000000003',
  item: 'Dish soap',
  quantity: null,
  note: null,
  status: 'REMOVED',
  removedBy: 'U-JUNDA',
  removedAt: '2026-07-25T10:20:00+09:00',
  currentMutationId: '10000000-0000-0000-0000-000000000003',
}

function mutation(overrides = {}) {
  return {
    isPending: false,
    mutateAsync: vi.fn(),
    ...overrides,
  }
}

function renderPage() {
  return renderWithProviders(<ShoppingPage />, { route: '/shopping' })
}

describe('ShoppingPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseShoppingItems.mockReturnValue({
      data: [milk, eggs, soap],
      isPending: false,
      isError: false,
    })
    mockUseUpdateShoppingItem.mockReturnValue(mutation())
    mockUseRemoveShoppingItem.mockReturnValue(mutation())
    mockUseRestoreShoppingItem.mockReturnValue(mutation())
  })

  it('shows active items without any web Add affordance', () => {
    renderPage()

    expect(screen.getAllByRole('heading', { name: 'Milk' })).not.toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Active 1' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'History 2' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /add/i })).not.toBeInTheDocument()
    expect(screen.getByText('New items start in Slack')).toBeInTheDocument()
  })

  it('shows bought and removed history but restores only removed items', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'History 2' }))

    expect(screen.getAllByRole('heading', { name: 'Eggs' })).not.toHaveLength(0)
    expect(screen.getAllByRole('heading', { name: 'Dish soap' })).not.toHaveLength(0)
    expect(screen.getAllByText('Bought')).not.toHaveLength(0)
    expect(screen.getAllByText('Removed')).not.toHaveLength(0)
    expect(screen.getAllByRole('button', { name: 'Restore' })).not.toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Restore Eggs' })).not.toBeInTheDocument()
  })

  it('opens bought history as read-only details', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'History 2' }))
    await user.click(screen.getAllByRole('button', { name: 'View' })[0])

    expect(screen.getByRole('dialog', { name: 'Bought Eggs' })).toBeInTheDocument()
    expect(screen.getByText('Bought item')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Restore to active list' }))
      .not.toBeInTheDocument()
  })

  it('edits an active item and sends its current mutation version', async () => {
    const user = userEvent.setup()
    const updated = {
      ...milk,
      item: 'Whole milk',
      quantity: '2 cartons',
      note: 'for breakfast',
      currentMutationId: '20000000-0000-0000-0000-000000000001',
    }
    const updateMutation = mutation({
      mutateAsync: vi.fn().mockResolvedValue(updated),
    })
    mockUseUpdateShoppingItem.mockReturnValue(updateMutation)
    renderPage()

    await user.click(screen.getAllByRole('button', { name: 'Edit Milk' })[0])
    const name = screen.getByRole('textbox', { name: 'Item' })
    const quantity = screen.getByRole('textbox', { name: 'Quantity' })
    const note = screen.getByRole('textbox', { name: 'Note' })
    await user.clear(name)
    await user.type(name, 'Whole milk')
    await user.clear(quantity)
    await user.type(quantity, '2 cartons')
    await user.clear(note)
    await user.type(note, 'for breakfast')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(updateMutation.mutateAsync).toHaveBeenCalledWith({
      id: milk.id,
      currentMutationId: milk.currentMutationId,
      item: 'Whole milk',
      quantity: '2 cartons',
      note: 'for breakfast',
    })
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent('Whole milk updated')
    })
  })

  it('removes from the editor and Undo restores the returned item version', async () => {
    const user = userEvent.setup()
    const removed = {
      ...milk,
      status: 'REMOVED',
      removedAt: '2026-07-27T09:12:00+09:00',
      currentMutationId: '30000000-0000-0000-0000-000000000001',
    }
    const restored = {
      ...removed,
      status: 'PENDING',
      removedAt: null,
      currentMutationId: '40000000-0000-0000-0000-000000000001',
    }
    const removeMutation = mutation({
      mutateAsync: vi.fn().mockResolvedValue(removed),
    })
    const restoreMutation = mutation({
      mutateAsync: vi.fn().mockResolvedValue(restored),
    })
    mockUseRemoveShoppingItem.mockReturnValue(removeMutation)
    mockUseRestoreShoppingItem.mockReturnValue(restoreMutation)
    renderPage()

    await user.click(screen.getAllByRole('button', { name: 'Edit Milk' })[0])
    await user.click(screen.getByRole('button', { name: 'Remove from list' }))

    expect(removeMutation.mutateAsync).toHaveBeenCalledWith({
      id: milk.id,
      currentMutationId: milk.currentMutationId,
    })
    await user.click(await screen.findByRole('button', { name: 'Undo' }))

    expect(restoreMutation.mutateAsync).toHaveBeenCalledWith({
      id: removed.id,
      currentMutationId: removed.currentMutationId,
    })
    expect(await screen.findByRole('status')).toHaveTextContent('Milk restored to the list')
  })

  it('shows recoverable loading, empty, and error states', () => {
    mockUseShoppingItems.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    })
    renderPage()

    expect(screen.getByText(/could not load the shopping list/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
