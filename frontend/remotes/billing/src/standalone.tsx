import type { ShellContext } from '@suite/shell-sdk'
import { createBrowserHistory } from '@tanstack/react-router'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import remote from './app'

const history = createBrowserHistory()

const context: ShellContext = {
  basepath: '/',
  history,
  getToken: async () => '',
  user: { id: '', email: '', displayName: '', scope: 'TENANT', tenantId: null, permissions: [] },
  tenant: null,
  theme: 'light',
  locale: 'en',
  navigate: (to) => history.push(to),
  registerCommands: () => () => {},
}

const root = document.getElementById('root')
if (!root) throw new Error('the remote page has no root element')

createRoot(root).render(
  <StrictMode>
    <remote.Root ctx={context} />
  </StrictMode>,
)
