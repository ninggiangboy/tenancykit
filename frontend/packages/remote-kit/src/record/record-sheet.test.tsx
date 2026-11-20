import { Dialog } from '@suite/ui'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { RecordSheet } from './record-sheet'

/** Radix attaches its outside-pointer listener one tick after mount, then waits for the click that follows. */
const nextTick = () => new Promise((resolve) => setTimeout(resolve, 0))

function press(target: Element) {
  fireEvent.pointerDown(target, { button: 0 })
  fireEvent.click(target)
}

describe('RecordSheet', () => {
  it('stays open for a press inside a child dialog', async () => {
    const onClose = vi.fn()
    render(
      <RecordSheet open onClose={onClose} title="Invoice INV-1">
        <Dialog open onClose={() => {}} title="Void invoice">
          This cannot be undone.
        </Dialog>
      </RecordSheet>,
    )
    await nextTick()

    press(screen.getByText('This cannot be undone.'))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on the scrim', async () => {
    const onClose = vi.fn()
    render(
      <RecordSheet open onClose={onClose} title="Invoice INV-1">
        body
      </RecordSheet>,
    )
    await nextTick()

    press(document.body)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
