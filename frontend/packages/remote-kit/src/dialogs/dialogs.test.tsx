import { act, fireEvent, screen } from '@testing-library/react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describe, expect, it, vi } from 'vitest'
import { renderKit } from '../test-utils'
import { ConfirmDialog } from './confirm-dialog'
import type { DialogAction } from './dialog-action'
import { FormDialog } from './form-dialog'

function Harness({ action, kind }: { action: DialogAction; kind: 'form' | 'confirm' }) {
  const [open, setOpen] = useState(true)
  const form = useForm({ defaultValues: { name: '' } })
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        reopen
      </button>
      {kind === 'form' ? (
        <FormDialog
          open={open}
          onClose={() => setOpen(false)}
          title="Rename"
          submitLabel="Save"
          action={action}
          form={form}
          onSubmit={() => {}}
        >
          <input aria-label="name" {...form.register('name')} />
        </FormDialog>
      ) : (
        <ConfirmDialog
          open={open}
          onClose={() => setOpen(false)}
          title="Void"
          confirmLabel="Void invoice"
          action={action}
          onConfirm={() => {}}
        />
      )}
    </>
  )
}

describe.each(['form', 'confirm'] as const)('%s dialog and the mutation it drives', (kind) => {
  it('shows the server sentence inside the dialog while the action has failed', () => {
    const reset = vi.fn()
    renderKit(
      <Harness kind={kind} action={{ error: { detail: 'Invoice is already void' }, isPending: false, reset }} />,
    )
    expect(screen.getByRole(kind === 'form' ? 'dialog' : 'alertdialog')).toHaveTextContent('Invoice is already void')
  })

  it('shows no alert before anything failed, and a pending action puts the button in its loading state', () => {
    renderKit(<Harness kind={kind} action={{ error: null, isPending: true, reset: () => {} }} />)
    expect(screen.queryByText('That did not work')).toBeNull()
    const confirm = screen.getByRole('button', { name: kind === 'form' ? 'Save' : 'Void invoice' })
    expect(confirm).toHaveAttribute('aria-busy', 'true')
  })

  it('clears the last failure when it opens and when it closes', async () => {
    const reset = vi.fn()
    renderKit(<Harness kind={kind} action={{ error: { detail: 'x' }, isPending: false, reset }} />)
    expect(reset).toHaveBeenCalledTimes(1)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    })
    expect(reset).toHaveBeenCalledTimes(2)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'reopen', hidden: true }))
    })
    expect(reset).toHaveBeenCalledTimes(3)
  })
})
