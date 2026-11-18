import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

export interface HealthOptions {
  /** A status the test itself provokes, such as the 403 of the permission test. */
  allowStatus?: (status: number, url: string) => boolean
}

export interface ConsoleHealth {
  problems: string[]
  expectClean(): Promise<void>
}

/**
 * The console health rule: a browser error, a refused resource or a CSP violation during a suite is a failure. It is
 * attached before the first navigation so a failure while booting is caught too.
 */
export function watchConsole(page: Page, options: HealthOptions = {}): ConsoleHealth {
  const problems: string[] = []

  page.on('console', (message) => {
    if (message.type() !== 'error') return
    const text = message.text()
    // Chromium logs one line per failed resource without the URL in its text; the location carries it, so an answer
    // the test already allows is not counted twice.
    const refused = /status of (\d{3})/.exec(text)
    if (refused && options.allowStatus?.(Number(refused[1]), message.location().url)) return
    problems.push(`console: ${text}`)
  })
  page.on('pageerror', (error) => {
    problems.push(`page: ${error.message}`)
  })
  page.on('requestfailed', (request) => {
    const failure = request.failure()?.errorText ?? ''
    if (failure.includes('net::ERR_ABORTED')) return
    problems.push(`request: ${request.url()} ${failure}`)
  })
  page.on('response', (response) => {
    const status = response.status()
    if (status < 400) return
    if (options.allowStatus?.(status, response.url())) return
    problems.push(`response: ${status} ${response.url()}`)
  })

  return {
    problems,
    async expectClean() {
      const violations = (await page.evaluate(
        () => (globalThis as unknown as { __cspViolations?: string[] }).__cspViolations ?? [],
      )) as string[]
      await expect([...violations.map((violation) => `csp: ${violation}`), ...problems], 'console health').toEqual([])
    },
  }
}

/** Records every CSP violation the page reports, before any script runs. */
export async function recordCspViolations(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const store = globalThis as unknown as { __cspViolations?: string[] }
    store.__cspViolations = []
    document.addEventListener('securitypolicyviolation', (event) => {
      store.__cspViolations?.push(`${event.violatedDirective} ${event.blockedURI}`)
    })
  })
}
