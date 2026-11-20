import { RemoteRoot } from '@suite/remote-kit'
import { defineRemote, SDK_MAJOR, type ShellContext } from '@suite/shell-sdk'
import { createI18n } from './i18n'
import { screens, sections } from './routes'
import './index.css'

function Root({ ctx }: { ctx: ShellContext }) {
  return <RemoteRoot ctx={ctx} createI18n={createI18n} screens={screens} sections={sections} />
}

export default defineRemote({ name: 'billing', sdkMajor: SDK_MAJOR, Root })
