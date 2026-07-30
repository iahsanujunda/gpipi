import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiFetch } from '@/api/http'
import MoneyMovementEditor from '@/wallets/MoneyMovementEditor'
import { renderWithProviders } from '@/test/renderWithProviders'

vi.mock('@/api/http', () => ({
  apiFetch: vi.fn(),
}))

const everyday = {
  id: '90000000-0000-0000-0000-000000000001',
  name: 'Everyday account',
  balance: 28_400,
  assignedBudgetCount: 3,
}

const savings = {
  id: '90000000-0000-0000-0000-000000000002',
  name: 'Savings',
  balance: 50_000,
  assignedBudgetCount: 1,
}

function projection(amount) {
  return {
    calculatedAt: '2026-07-29T03:45:00Z',
    accounts: [{
      accountId: everyday.id,
      name: everyday.name,
      balanceBefore: 28_400,
      delta: amount,
      balanceAfter: 28_400 + amount,
    }],
  }
}

function renderEditor(mutation, overrides = {}) {
  const props = {
    accounts: [everyday, savings],
    initialToAccountId: everyday.id,
    mutation,
    onClose: vi.fn(),
    onExited: vi.fn(),
    onSaved: vi.fn(),
    open: true,
    ...overrides,
  }
  renderWithProviders(<MoneyMovementEditor {...props} />)
  return props
}

describe('MoneyMovementEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('opens as an external top-up and swap preserves the rest of the input', async () => {
    const user = userEvent.setup()
    apiFetch.mockResolvedValue(projection(20_000))
    const mutation = { isPending: false, mutateAsync: vi.fn() }
    renderEditor(mutation)

    expect(screen.getByRole('combobox', { name: 'From' })).toHaveTextContent('External account')
    expect(screen.getByRole('combobox', { name: 'To' })).toHaveTextContent('Everyday account')

    await user.type(screen.getByRole('textbox', { name: 'Amount' }), '20000')
    await user.type(screen.getByRole('textbox', { name: 'Note' }), 'July salary')
    const dateInput = document.querySelector('input[type="date"]')
    const date = dateInput.value
    await user.click(screen.getByRole('button', { name: 'Swap From and To' }))

    expect(screen.getByRole('combobox', { name: 'From' })).toHaveTextContent('Everyday account')
    expect(screen.getByRole('combobox', { name: 'To' })).toHaveTextContent('External account')
    expect(screen.getByRole('textbox', { name: 'Amount' })).toHaveValue('20000')
    expect(dateInput).toHaveValue(date)
    expect(screen.getByRole('textbox', { name: 'Note' })).toHaveValue('July salary')
  })

  it('keeps one idempotency key through an ambiguous failure and retry', async () => {
    const user = userEvent.setup()
    const writeResult = {
      movement: { id: 'movement-1' },
      calculatedAt: '2026-07-29T03:45:01Z',
      accounts: projection(20_000).accounts,
    }
    apiFetch.mockResolvedValue(projection(20_000))
    const mutation = {
      isPending: false,
      mutateAsync: vi.fn()
        .mockRejectedValueOnce(new Error('Connection lost'))
        .mockResolvedValueOnce(writeResult),
    }
    const props = renderEditor(mutation)

    await user.type(screen.getByRole('textbox', { name: 'Amount' }), '20000')
    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    await user.click(screen.getByRole('button', { name: 'Review money movement' }))
    await user.click(screen.getByRole('button', { name: 'Record money movement' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Connection lost')
    await user.click(screen.getByRole('button', { name: 'Record money movement' }))

    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalledTimes(2))
    const firstKey = mutation.mutateAsync.mock.calls[0][0].idempotencyKey
    const secondKey = mutation.mutateAsync.mock.calls[1][0].idempotencyKey
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/i)
    expect(secondKey).toBe(firstKey)
    expect(props.onSaved).toHaveBeenCalledWith(writeResult)
    expect(props.onClose).toHaveBeenCalledOnce()
  })

  it('renders only the newest preview when an older request finishes last', async () => {
    const user = userEvent.setup()
    const pending = []
    apiFetch.mockImplementation((_path, options) => new Promise((resolve) => {
      pending.push({ amount: options.body.amount, resolve })
    }))
    const mutation = { isPending: false, mutateAsync: vi.fn() }
    renderEditor(mutation)
    const amount = screen.getByRole('textbox', { name: 'Amount' })

    await user.type(amount, '100')
    await waitFor(() => expect(pending).toHaveLength(1))
    await user.clear(amount)
    await user.type(amount, '200')
    await waitFor(() => expect(pending).toHaveLength(2))

    pending[1].resolve(projection(200))
    expect(await screen.findByText('¥28,400 → ¥28,600')).toBeInTheDocument()
    pending[0].resolve(projection(100))

    await waitFor(() => {
      expect(screen.getByText('¥28,400 → ¥28,600')).toBeInTheDocument()
      expect(screen.queryByText('¥28,400 → ¥28,500')).not.toBeInTheDocument()
    })
  })
})
