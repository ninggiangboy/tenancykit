import type { RemoteModule } from '@suite/shell-sdk'
import type { ConsoleRemote } from './contracts'

/** What the remote host needs from a loader; the real one verifies the manifest, a test stands in. */
export interface RemoteLoader {
  load(remote: ConsoleRemote): Promise<RemoteModule>
}
