import { createBrowserHistory } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ShellContext } from './index'
import { can, ShellProvider, usePermission } from './react'

function context(permissions: string[] = [], register = vi.fn(() => () => {})): ShellContext {
  return {
    basepath: '/console/r/billing',
    history: createBrowserHistory(),
    getToken: async () => 'token',
    user: { id: '1', email: 'ada@example.test', displayName: 'Ada', scope: 'TENANT', tenantId: 't1', permissions },
    tenant: { id: 't1', code: 'acme', name: 'Acme' },
    theme: 'light',
    locale: 'en',
    navigate: () => {},
    registerCommands: register,
  }
}

function Gate({ code }: { code: string }) {
  return usePermission(code) ? <p>allowed</p> : <p>hidden</p>
}

describe('shell context', () => {
  it('answers a permission the user holds', () => {
    expect(can(context(['billing:invoice:read']), 'billing:invoice:read')).toBe(true)
    expect(can(context(['billing:invoice:read']), 'billing:invoice:manage')).toBe(false)
  })

  it('shows a control only when the permission is held', () => {
    render(
      <ShellProvider ctx={context(['billing:invoice:read'])}>
        <Gate code="billing:invoice:read" />
      </ShellProvider>,
    )

    expect(screen.getByText('allowed')).toBeInTheDocument()
  })

  it('refuses to render a remote without the shell context', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Gate code="x" />)).toThrow(/inside the shell context/)
    quiet.mockRestore()
  })
})
