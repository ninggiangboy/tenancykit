import * as CheckboxPrimitive from '@radix-ui/react-checkbox'
import { type ComponentPropsWithoutRef, type ElementRef, forwardRef } from 'react'
import { cn } from './cn'
import { Icon } from './icon'

export interface CheckboxProps extends ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root> {
  label?: React.ReactNode
  description?: React.ReactNode
  indeterminate?: boolean
}

/** A boolean, in a form or as the selection control of a table row. */
export const Checkbox = forwardRef<ElementRef<typeof CheckboxPrimitive.Root>, CheckboxProps>(function Checkbox(
  { className, label, description, indeterminate, id, ...props },
  ref,
) {
  return (
    <div className="flex items-start gap-2">
      <CheckboxPrimitive.Root
        ref={ref}
        id={id}
        checked={indeterminate ? 'indeterminate' : props.checked}
        className={cn(
          'mt-0.5 box-border size-4 shrink-0 rounded-sm border border-input bg-background shadow-xs',
          'data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground',
          'data-[state=indeterminate]:border-primary data-[state=indeterminate]:bg-primary data-[state=indeterminate]:text-primary-foreground',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          className,
        )}
        {...props}
      >
        <CheckboxPrimitive.Indicator className="flex items-center justify-center">
          <Icon name={indeterminate ? 'x' : 'check'} size={12} strokeWidth={3} />
        </CheckboxPrimitive.Indicator>
      </CheckboxPrimitive.Root>
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
