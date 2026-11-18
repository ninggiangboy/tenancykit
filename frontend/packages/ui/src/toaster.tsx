import { useCallback, useEffect, useRef, useState } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { useUiMessages } from './messages'

export interface Toast {
  id: number
  title: React.ReactNode
  description?: React.ReactNode
  tone?: 'success' | 'danger' | 'info'
  duration?: number
  action?: { label: string; icon?: IconName; onClick: () => void }
}

export interface ToasterProps {
  toasts: Toast[]
  onDismiss: (id: number) => void
  contained?: boolean
}

const toneIcon: Record<NonNullable<Toast['tone']>, IconName> = {
  success: 'check-circle',
  danger: 'x-circle',
  info: 'info',
}

const toneClass = {
  success: 'text-success',
  danger: 'text-destructive',
  info: 'text-info',
} as const

/** Past tense, one line, and an Undo action where the change can be taken back. */
export function Toaster({ toasts, onDismiss, contained }: ToasterProps) {
  const t = useUiMessages()
  return (
    <div
      aria-live="polite"
      className={cn(
        'pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2',
        contained && 'absolute',
      )}
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role="status"
          className="pointer-events-auto flex items-start gap-2.5 rounded-lg border border-border bg-popover p-3 text-popover-foreground shadow-lg"
        >
          <Icon
            name={toneIcon[toast.tone ?? 'info']}
            size={16}
            className={cn('mt-0.5', toneClass[toast.tone ?? 'info'])}
          />
          <div className="flex flex-1 flex-col gap-0.5">
            <p className="text-body-strong text-foreground">{toast.title}</p>
            {toast.description && <p className="text-small text-muted-foreground">{toast.description}</p>}
          </div>
          {toast.action && (
            <button
              type="button"
              onClick={() => {
                toast.action?.onClick()
                onDismiss(toast.id)
              }}
              className="rounded-md px-1.5 py-0.5 text-small font-medium text-brand hover:bg-accent"
            >
              {toast.action.icon && <Icon name={toast.action.icon} size={12} className="mr-1 inline" />}
              {toast.action.label}
            </button>
          )}
          <button
            type="button"
            aria-label={t('action.close')}
            onClick={() => onDismiss(toast.id)}
            className="rounded-md p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <Icon name="x" size={12} />
          </button>
        </div>
      ))}
    </div>
  )
}

/**
 * [toasts, push, dismiss]. Keeps the last three, and each leaves after its duration (six seconds by default). The timer
 * is cleared when the caller dismisses early, so a closed toast never comes back.
 */
export function useToasts(duration = 6000): [Toast[], (toast: Omit<Toast, 'id'>) => number, (id: number) => void] {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(1)
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>())

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.current.delete(id)
    }
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const push = useCallback(
    (toast: Omit<Toast, 'id'>) => {
      const id = nextId.current++
      setToasts((current) => [...current.slice(-2), { ...toast, id }])
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), toast.duration ?? duration),
      )
      return id
    },
    [dismiss, duration],
  )

  useEffect(() => {
    const pending = timers.current
    return () => {
      for (const timer of pending.values()) {
        clearTimeout(timer)
      }
      pending.clear()
    }
  }, [])

  return [toasts, push, dismiss]
}
