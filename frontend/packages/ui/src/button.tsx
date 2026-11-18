import { cva, type VariantProps } from 'class-variance-authority'
import { type ButtonHTMLAttributes, forwardRef } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { Kbd } from './kbd'
import type { KeyName } from './keys'

const button = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium transition-colors duration-[120ms] disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground shadow-xs hover:bg-primary/90',
        brand: 'bg-brand text-brand-foreground shadow-xs hover:bg-brand/90',
        secondary: 'bg-secondary text-secondary-foreground hover:bg-secondary/80',
        outline: 'border border-input bg-background shadow-xs hover:bg-accent hover:text-accent-foreground',
        ghost: 'hover:bg-accent hover:text-accent-foreground',
        destructive: 'bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90',
        link: 'text-brand underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-7 px-2 text-small',
        md: 'h-8 px-3 text-body-strong',
        lg: 'h-10 px-4 text-body-strong',
      },
      iconOnly: {
        true: 'px-0',
        false: '',
      },
    },
    compoundVariants: [
      { iconOnly: true, size: 'sm', class: 'w-7' },
      { iconOnly: true, size: 'md', class: 'w-8' },
      { iconOnly: true, size: 'lg', class: 'w-10' },
    ],
    defaultVariants: { variant: 'default', size: 'md', iconOnly: false },
  },
)

export interface ButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'>,
    VariantProps<typeof button> {
  icon?: IconName
  iconEnd?: IconName
  /** Square, icon-only: `children` must be a string and becomes the aria-label. Wrap it in `Tooltip`. */
  iconOnly?: boolean
  /** Disables the button and swaps the icon for a spinner while a request runs. */
  loading?: boolean
  /** Shows the shortcut that does the same thing, for example `['mod', 'k']`. */
  shortcut?: KeyName[]
  children?: React.ReactNode
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant, size, icon, iconEnd, iconOnly, loading, shortcut, disabled, children, ...props },
  ref,
) {
  const label = iconOnly && typeof children === 'string' ? children : undefined
  return (
    <button
      ref={ref}
      type={props.type ?? 'button'}
      className={cn(button({ variant, size, iconOnly }), className)}
      disabled={disabled || loading}
      aria-label={props['aria-label'] ?? label}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? (
        <Icon name="loader" size={size === 'sm' ? 14 : 16} className="animate-spin" />
      ) : (
        icon && <Icon name={icon} size={size === 'sm' ? 14 : 16} />
      )}
      {iconOnly ? <span className="sr-only">{children}</span> : children}
      {iconEnd && !loading && <Icon name={iconEnd} size={size === 'sm' ? 14 : 16} />}
      {shortcut && <Kbd keys={shortcut} className="ml-1" />}
    </button>
  )
})
