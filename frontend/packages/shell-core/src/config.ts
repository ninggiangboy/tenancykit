/** The runtime configuration the static server renders into `/console/config.json`; nothing in it is secret. */
export interface ConsoleConfig {
  authority: string
  clientId: string
  platformApi: string
  billingApi: string
}

export const CONFIG_URL = '/console/config.json'

/** Loads the one file that differs per environment, so no shell is rebuilt for a host or an authority. */
export async function loadConsoleConfig(
  fetcher: typeof fetch = globalThis.fetch,
  url = CONFIG_URL,
): Promise<ConsoleConfig> {
  const response = await fetcher(url, { cache: 'no-cache' })
  if (!response.ok) {
    throw new Error(`the console configuration is not available (HTTP ${response.status})`)
  }
  const config = (await response.json()) as Partial<ConsoleConfig>
  if (!config.authority || !config.clientId) {
    throw new Error('the console configuration names no Keycloak authority or client')
  }
  return {
    authority: config.authority,
    clientId: config.clientId,
    platformApi: config.platformApi ?? '/platform',
    billingApi: config.billingApi ?? '',
  }
}
