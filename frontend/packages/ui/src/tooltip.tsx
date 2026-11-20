import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import { Kbd } from './kbd'
import type { KeyName } from './keys'

export interface TooltipProps {
  content: React.ReactNode
  shortcut?: KeyName[]
  children: React.ReactNode
  defaultOpen?: boolean
}

/** Names an icon-only control and its shortcut: after a hover delay, and at once on focus. */
export function Tooltip({ content, shortcut, children, defaultOpen }: TooltipProps) {
  return (
    // Radix requires a provider above every root; a tooltip provides its own so it works wherever it is placed.
    <TooltipPrimitive.Provider delayDuration={400}>
      <TooltipPrimitive.Root defaultOpen={defaultOpen}>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <TooltipPrimitive.Content
            sideOffset={6}
            className="z-50 flex items-center gap-1.5 rounded-md bg-primary px-2 py-1 text-caption text-primary-foreground shadow-md"
          >
            {content}
            {shortcut && (
              <Kbd
                keys={shortcut}
                className="border-primary-foreground/20 bg-primary-foreground/10 text-primary-foreground"
              />
            )}
            <TooltipPrimitive.Arrow className="fill-primary" />
          </TooltipPrimitive.Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  )
}
