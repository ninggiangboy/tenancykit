import { useEffect, useState } from 'react'
import { cn } from './cn'
import { Icon } from './icon'
import { useUiMessages } from './messages'

export interface CopyIdProps {
  value: string
  children?: React.ReactNode
  mono?: boolean
  onCopy?: (value: string) => void
  className?: string
}

/** An identifier that copies itself on click and confirms with a check. */
export function CopyId({ value, children, mono = true, onCopy, className }: CopyIdProps) {
  const t = useUiMessages()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) {
      return
    }
    const timer = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(timer)
  }, [copied])

  return (
    <button
      type="button"
      title={value}
      aria-label={`${t('action.copy')} ${value}`}
      className={cn(
        'group inline-flex max-w-full items-center gap-1 rounded-sm text-left text-body text-foreground hover:text-foreground',
        mono && 'font-mono text-code',
        className,
      )}
      onClick={async () => {
        try {
          await navigator.clipboard?.writeText(value)
        } catch {
          // A browser that refuses the clipboard still leaves the value selectable; the check would be a lie.
          return
        }
        setCopied(true)
        onCopy?.(value)
      }}
    >
      <span className="truncate">{children ?? value}</span>
      <Icon
        name={copied ? 'check' : 'copy'}
        size={12}
        className={cn(
          'shrink-0 text-muted-foreground transition-[opacity,width,margin] duration-[120ms]',
          'w-0 -ml-1.5 opacity-0 group-hover:ml-0 group-hover:w-3.5 group-hover:opacity-100',
          'group-focus-visible:ml-0 group-focus-visible:w-3.5 group-focus-visible:opacity-100',
          '[@media(hover:none)]:ml-0 [@media(hover:none)]:w-3.5 [@media(hover:none)]:opacity-100',
          copied && 'ml-0 w-3.5 text-success opacity-100',
        )}
      />
    </button>
  )
}
