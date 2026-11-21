import * as SwitchPrimitive from '@radix-ui/react-switch'
import { type ComponentPropsWithoutRef, type ElementRef, forwardRef } from 'react'
import { cn } from './cn'
import { Icon } from './icon'

export interface SwitchProps extends ComponentPropsWithoutRef<typeof SwitchPrimitive.Root> {
  label?: React.ReactNode
  description?: React.ReactNode
  /** The setting is saving: the knob keeps its place, shows a spinner and the switch reports busy without dimming. */
  busy?: boolean
}

/**
 * An on/off setting that takes effect at once. It is optimistic: the caller moves it, and flips it back with a toast
 * when the request fails.
 */
export const Switch = forwardRef<ElementRef<typeof SwitchPrimitive.Root>, SwitchProps>(function Switch(
  { className, label, description, busy, id, disabled, ...props },
  ref,
) {
  return (
    <div className="flex items-start gap-2">
      <SwitchPrimitive.Root
        ref={ref}
        id={id}
        disabled={disabled || busy}
        aria-busy={busy || undefined}
        data-busy={busy ? '' : undefined}
        className={cn(
          'motion-colors peer relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent',
          // On a phone the 36x20 track is still a 44px target.
          "before:absolute before:-inset-x-1 before:-inset-y-3 before:content-[''] md:before:hidden",
          'data-[state=checked]:bg-primary data-[state=unchecked]:bg-input',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          // Saving is not disabled: the control keeps its full colour and shows the wait on its knob.
          'data-[busy]:disabled:cursor-progress data-[busy]:disabled:opacity-100',
          className,
        )}
        {...props}
      >
        <SwitchPrimitive.Thumb className="pointer-events-none flex size-4 items-center justify-center rounded-full bg-background shadow-sm transition-transform duration-(--duration-fast) ease-out data-[state=checked]:translate-x-4 data-[state=unchecked]:translate-x-0">
          {busy && <Icon name="loader" size={12} className="animate-spin text-muted-foreground" />}
        </SwitchPrimitive.Thumb>
      </SwitchPrimitive.Root>
      {(label || description) && (
        <div className="flex flex-col">
          {label && (
            <label htmlFor={id} className="text-body-strong text-foreground">
              {label}
            </label>
          )}
          {description && <span className="text-small text-muted-foreground">{description}</span>}
        </div>
      )}
    </div>
  )
})
