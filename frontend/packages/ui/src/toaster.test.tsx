import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { act, useState } from 'react'
import { describe, expect, it } from 'vitest'
import { type Toast, Toaster, useToasts } from './toaster'

function Harness() {
  const [toasts, push, dismiss] = useToasts()
  return (
    <div>
      <button type="button" onClick={() => push({ title: 'Billing enabled for acme', tone: 'success' })}>
        enable
      </button>
      <button type="button" onClick={() => push({ title: 'Failed', tone: 'danger', duration: 60_000 })}>
        fail
      </button>
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </div>
  )
}

function Many() {
  const [toasts, push, dismiss] = useToasts()
  return (
    <div>
      <button
        type="button"
        onClick={() => {
          for (const n of [1, 2, 3, 4]) {
            push({ title: `Toast ${n}` })
          }
        }}
      >
        push
      </button>
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </div>
  )
}

function Controlled({ initial }: { initial: Omit<Toast, 'id'> }) {
  const [toasts, push, dismiss] = useToasts()
  const [pushed, setPushed] = useState(false)
  if (!pushed) {
    setPushed(true)
    act(() => {
      push(initial)
    })
  }
  return <Toaster toasts={toasts} onDismiss={dismiss} />
}

describe('Toaster', () => {
  it('shows a pushed toast and takes it away', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'enable' }))
    expect(screen.getByText('Billing enabled for acme')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    // The toast plays its leave animation first, then goes.
    expect(screen.getByRole('status')).toHaveAttribute('data-state', 'closed')
    await waitFor(() => expect(screen.queryByText('Billing enabled for acme')).not.toBeInTheDocument())
  })

  it('runs the Undo action and closes the toast', async () => {
    let undone = 0
    render(<Controlled initial={{ title: 'Filter removed', action: { label: 'Undo', onClick: () => undone++ } }} />)

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }))

    expect(undone).toBe(1)
    await waitFor(() => expect(screen.queryByText('Filter removed')).not.toBeInTheDocument())
  })

  it('keeps the last three toasts', async () => {
    render(<Many />)
    await userEvent.click(screen.getByRole('button', { name: 'push' }))

    expect(screen.queryByText('Toast 1')).not.toBeInTheDocument()
    expect(screen.getByText('Toast 4')).toBeInTheDocument()
  })
})
