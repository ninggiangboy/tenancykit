import { ensureRemoteActive } from './stack'

/**
 * The suite's baseline: both remotes are enabled and point at the newest published version. Publishing never activates
 * by itself, and the tests that prove the operator's click do it through the UI; everything else starts from here.
 */
export default async function globalSetup(): Promise<void> {
  await ensureRemoteActive('platform')
  await ensureRemoteActive('billing')
}
