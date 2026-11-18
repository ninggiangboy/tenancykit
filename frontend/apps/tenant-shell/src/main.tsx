import '@fontsource-variable/inter'
import '@fontsource-variable/geist-mono'
import '@fontsource/be-vietnam-pro/400.css'
import '@fontsource/be-vietnam-pro/500.css'
import '@fontsource/be-vietnam-pro/600.css'
import './index.css'
import { ConsoleAuth } from '@suite/auth'
import { loadConsoleConfig, startShell } from '@suite/shell-core'
import { definition } from './definition'

async function main() {
  const config = await loadConsoleConfig()
  await startShell({ definition, auth: ConsoleAuth.fromConfig(config), config })
}

void main()
