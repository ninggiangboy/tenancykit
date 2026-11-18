import { cn } from './cn'
import { type KeyName, keyLabel } from './keys'

/** Keyboard keys, rendered the way the platform writes them. */
export function Kbd({
  keys,
  children,
  className,
}: {
  keys?: KeyName[]
  children?: React.ReactNode
  className?: string
}) {
  return (
    <kbd
      className={cn(
        'inline-flex h-5 items-center gap-0.5 rounded-sm border border-border bg-muted px-1 font-sans text-caption text-muted-foreground',
        className,
      )}
    >
      {keys ? keys.map((key) => <span key={key}>{keyLabel(key)}</span>) : children}
    </kbd>
  )
}
