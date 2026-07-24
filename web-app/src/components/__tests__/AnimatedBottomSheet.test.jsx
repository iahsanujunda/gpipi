import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fireEvent,
  render,
  screen,
  waitForElementToBeRemoved,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useMediaQuery } from '@mui/material'
import AnimatedBottomSheet from '@/components/AnimatedBottomSheet'

vi.mock('@mui/material', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useMediaQuery: vi.fn(() => false) }
})

function Sheet({ open = true, onClose = vi.fn(), children = 'Body' }) {
  return (
    <AnimatedBottomSheet
      open={open}
      onClose={onClose}
      history={false}
      slotProps={{ paper: { role: 'dialog', 'aria-label': 'Test sheet' } }}
    >
      {children}
    </AnimatedBottomSheet>
  )
}

describe('AnimatedBottomSheet', () => {
  beforeEach(() => vi.mocked(useMediaQuery).mockReturnValue(false))

  it('advertises bottom motion, swipe dismissal, and the global viewport reveal cap', () => {
    render(<Sheet />)

    const sheet = screen.getByRole('dialog', { name: 'Test sheet' })
    expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
    expect(sheet).toHaveAttribute('data-enter-duration-ms', '520')
    expect(sheet).toHaveAttribute('data-exit-duration-ms', '320')
    expect(sheet).toHaveAttribute('data-swipe-to-dismiss', 'true')
    expect(sheet).toHaveStyle({
      position: 'fixed',
      bottom: '0px',
      maxHeight:
        'min(var(--bottom-sheet-feature-max-height, 100dvh), calc(100dvh - max(24px, env(safe-area-inset-top))))',
    })
  })

  it('dismisses from the backdrop', () => {
    const onClose = vi.fn()
    render(<Sheet onClose={onClose} />)

    const backdrop = document.querySelector('.MuiBackdrop-root')
    fireEvent.mouseDown(backdrop)
    fireEvent.mouseUp(backdrop)
    fireEvent.click(backdrop)

    expect(onClose).toHaveBeenCalledWith(expect.anything(), 'backdropClick')
  })

  it('keeps the paper mounted until its exit motion completes', async () => {
    function Harness() {
      const [open, setOpen] = useState(true)
      return (
        <Sheet open={open}>
          <button onClick={() => setOpen(false)}>Close sheet</button>
        </Sheet>
      )
    }

    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Close sheet' }))

    const exitingSheet = screen.getByRole('dialog', { name: 'Test sheet', hidden: true })
    expect(exitingSheet).toBeInTheDocument()
    await waitForElementToBeRemoved(exitingSheet)
  })

  it('removes timed motion when reduced motion is requested', () => {
    vi.mocked(useMediaQuery).mockReturnValue(true)
    render(<Sheet />)

    const sheet = screen.getByRole('dialog', { name: 'Test sheet' })
    expect(sheet).toHaveAttribute('data-motion', 'reduced')
    expect(sheet).toHaveAttribute('data-enter-duration-ms', '0')
    expect(sheet).toHaveAttribute('data-exit-duration-ms', '0')
  })
})
