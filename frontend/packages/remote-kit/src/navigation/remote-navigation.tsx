import type { NavSection, ShellContext } from '@suite/shell-sdk'
import { useShell } from '@suite/shell-sdk/react'
import { cn } from '@suite/ui'
import { type ReactNode, useEffect, useRef, useSyncExternalStore } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'

/**
 * The remote's screens, already filtered by permission. A shell that takes navigation shows them in its sidebar; an
 * older shell (or the remote's standalone page) gets the same sections as a nav above the content.
 */
export function RemoteNavigation({ sections, children }: { sections: NavSection[]; children: ReactNode }) {
  const ctx = useShell()
  const hosted = typeof ctx.registerNavigation === 'function'
  // The context object is rebuilt on every shell render; the registration follows the sections, not the object.
  const register = useRef(ctx.registerNavigation)
  register.current = ctx.registerNavigation
  const signature = JSON.stringify(sections)

  useEffect(() => register.current?.(sections), [signature])

  return (
    <div className="flex flex-col">
      {!hosted && <LocalNav ctx={ctx} sections={sections} />}
      {children}
    </div>
  )
}

function usePathname(ctx: ShellContext): string {
  return useSyncExternalStore(
    (listener) => ctx.history.subscribe(listener),
    () => ctx.history.location.pathname,
  )
}

function LocalNav({ ctx, sections }: { ctx: ShellContext; sections: NavSection[] }) {
  const t = useKitT()
  const pathname = usePathname(ctx)
  return (
    <nav
      aria-label={t('nav.sections')}
      className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-border px-4 py-2"
    >
      {sections.map((section) => (
        <div key={section.id} className="flex items-center gap-1">
          {section.label && <span className="mr-1 text-caption text-muted-foreground">{section.label}</span>}
          {section.items.map((item) => {
            const href = `${ctx.basepath}${item.to}`
            const active = pathname === href || pathname.startsWith(`${href}/`)
            return (
              <a
                key={item.id}
                href={href}
                aria-current={active ? 'page' : undefined}
                onClick={(event) => {
                  event.preventDefault()
                  ctx.navigate(href)
                }}
                className={cn(
                  'motion-colors inline-flex h-(--size-control-sm) items-center rounded-md px-2 text-small text-muted-foreground hover:bg-accent hover:text-foreground',
                  active && 'bg-accent text-foreground',
                )}
              >
                {item.label}
              </a>
            )
          })}
        </div>
      ))}
    </nav>
  )
}
