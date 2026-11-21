import { useCallback, useEffect, useRef, useState } from 'react'
import { cn } from './cn'
import { Icon, type IconName } from './icon'
import { useUiMessages } from './messages'
import { LEAVE_MS } from './motion'

export interface Toast {
  id: number
  title: React.ReactNode
  description?: React.ReactNode
  tone?: 'success' | 'danger' | 'info'
  duration?: number
  /** Set while the toast plays its leave animation; the hook removes it a moment later. */
  closing?: boolean
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
        // On a phone the bottom edge belongs to the floating action and the bulk bar, so toasts come in under the top bar.
        'pointer-events-none fixed bottom-4 right-4 z-(--z-toast) flex w-80 flex-col gap-2 max-md:inset-x-3 max-md:top-16 max-md:bottom-auto max-md:w-auto',
        contained && 'absolute',
      )}
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role="status"
          data-state={toast.closing ? 'closed' : 'open'}
          className={cn(
            'pointer-events-auto flex items-start gap-2.5 rounded-lg border border-border bg-popover p-3 text-popover-foreground shadow-lg',
            'data-[state=closed]:pointer-events-none data-[state=closed]:animate-fade-out data-[state=open]:animate-rise-in',
          )}
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
              className="motion-colors flex min-h-7 items-center rounded-md px-2 text-small font-medium text-brand hover:bg-accent max-md:min-h-(--size-touch-target)"
            >
              {toast.action.icon && <Icon name={toast.action.icon} size={12} className="mr-1 inline" />}
              {toast.action.label}
            </button>
          )}
          <button
            type="button"
            aria-label={t('action.close')}
            onClick={() => onDismiss(toast.id)}
            className="motion-colors flex size-7 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground max-md:size-(--size-touch-target)"
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
 * is cleared when the caller dismisses early, so a closed toast never comes back. A dismissed toast stays for the leave
 * animation, marked `closing`, and is removed once it has played.
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
    setToasts((current) => current.map((toast) => (toast.id === id ? { ...toast, closing: true } : toast)))
    timers.current.set(
      -id,
      setTimeout(() => {
        timers.current.delete(-id)
        setToasts((current) => current.filter((toast) => toast.id !== id))
      }, LEAVE_MS),
    )
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
