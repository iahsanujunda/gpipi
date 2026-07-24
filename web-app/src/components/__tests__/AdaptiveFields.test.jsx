import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MenuItem, useMediaQuery } from '@mui/material'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AdaptiveDateField from '@/components/AdaptiveDateField'
import AdaptiveSelect from '@/components/AdaptiveSelect'
import { renderWithProviders } from '@/test/renderWithProviders'

vi.mock('@mui/material', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useMediaQuery: vi.fn(() => false) }
})

function mobileQuery(query) {
  return !String(query).includes('prefers-reduced-motion')
}

function SelectHarness() {
  const [value, setValue] = useState('NEWEST')
  return (
    <AdaptiveSelect
      name="sort"
      label="Sort"
      value={value}
      onChange={(event) => setValue(event.target.value)}
    >
      <MenuItem value="NEWEST">Newest first</MenuItem>
      <MenuItem value="OLDEST">Oldest first</MenuItem>
      <MenuItem value="DISABLED" disabled>Unavailable</MenuItem>
    </AdaptiveSelect>
  )
}

function DateHarness() {
  const [value, setValue] = useState('2026-07-24')
  return (
    <AdaptiveDateField
      name="from"
      label="From"
      value={value}
      onChange={(event) => setValue(event.target.value)}
    />
  )
}

describe('adaptive mobile fields', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useMediaQuery).mockImplementation(mobileQuery)
  })

  it('selects from an animated option sheet and restores focus after exit', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SelectHarness />)

    const trigger = screen.getByRole('combobox', { name: 'Sort' })
    await user.click(trigger)

    const sheet = screen.getByRole('dialog', { name: 'Sort' })
    expect(sheet).toHaveAttribute('data-presentation', 'bottom-sheet-options')
    expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
    expect(screen.getByRole('option', { name: 'Newest first' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('option', { name: 'Unavailable' })).toHaveAttribute('aria-disabled', 'true')

    await user.click(screen.getByRole('option', { name: 'Oldest first' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sort' })).not.toBeInTheDocument())
    expect(trigger).toHaveValue('Oldest first')
    expect(trigger).toHaveFocus()
  })

  it('selects a calendar day from a date sheet and restores focus', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DateHarness />)

    const trigger = screen.getByRole('combobox', { name: 'From' })
    await user.click(trigger)

    const sheet = screen.getByRole('dialog', { name: 'From' })
    expect(sheet).toHaveAttribute('data-presentation', 'bottom-sheet-date-picker')
    expect(screen.getByText('July 2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '24 July 2026' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: '25 July 2026' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'From' })).not.toBeInTheDocument())
    expect(trigger).toHaveValue('25 Jul 2026')
    expect(trigger).toHaveFocus()
  })

  it('navigates calendar months without invoking the field change', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DateHarness />)

    await user.click(screen.getByRole('combobox', { name: 'From' }))
    await user.click(screen.getByRole('button', { name: 'Next month' }))

    expect(screen.getByText('August 2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1 August 2026' })).toBeInTheDocument()
  })
})
