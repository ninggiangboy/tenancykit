import { cva, type VariantProps } from 'class-variance-authority'
import { type ButtonHTMLAttributes, forwardRef } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { Kbd } from './kbd'
import type { KeyName } from './keys'

const button = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium motion-colors disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground shadow-xs hover:bg-primary/90',
        brand: 'bg-brand text-brand-foreground shadow-xs hover:bg-brand/90',
        secondary: 'bg-secondary text-secondary-foreground hover:bg-secondary/80',
        outline: 'border border-input bg-background shadow-xs hover:bg-accent hover:text-accent-foreground',
        ghost: 'hover:bg-accent hover:text-accent-foreground',
        destructive: 'bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90',
        danger: 'text-destructive hover:bg-destructive-soft',
        link: 'text-link underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-(--size-control-sm) px-3 text-small',
        md: 'h-(--size-control-md) px-3.5 text-body-strong',
        lg: 'h-(--size-control-lg) px-5 text-body-strong',
      },
      iconOnly: {
        true: 'px-0',
        false: '',
      },
    },
    compoundVariants: [
      { iconOnly: true, size: 'sm', class: 'w-(--size-control-sm)' },
      { iconOnly: true, size: 'md', class: 'w-(--size-control-md)' },
      { iconOnly: true, size: 'lg', class: 'w-(--size-control-lg)' },
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
  const iconSize = size === 'sm' ? 14 : 16
  // With no icon to swap, the spinner goes over the label, which keeps its room: the button never changes width.
  const covering = loading === true && !icon && !iconOnly
  const content = (
    <>
      {loading && !covering ? (
        <Icon name="loader" size={iconSize} className="animate-spin" />
      ) : (
        icon && <Icon name={icon} size={iconSize} />
      )}
      {iconOnly ? <span className="sr-only">{children}</span> : children}
      {iconEnd && (covering || !loading) && <Icon name={iconEnd} size={iconSize} />}
      {shortcut && <Kbd keys={shortcut} className="ml-1" />}
    </>
  )
  return (
    <button
      ref={ref}
      type={props.type ?? 'button'}
      className={cn(
        button({ variant, size, iconOnly }),
        covering && 'relative',
        // Waiting is not disabled: the button keeps its full colour and shows the spinner.
        loading && 'disabled:opacity-100',
        className,
      )}
      disabled={disabled || loading}
      aria-label={props['aria-label'] ?? label}
      aria-busy={loading || undefined}
      {...props}
    >
      {covering && (
        <span aria-hidden className="absolute inset-0 flex items-center justify-center">
          <Icon name="loader" size={iconSize} className="animate-spin" />
        </span>
      )}
      {covering ? <span className="invisible contents">{content}</span> : content}
    </button>
  )
})
