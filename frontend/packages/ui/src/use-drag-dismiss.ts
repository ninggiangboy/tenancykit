import { type PointerEvent as ReactPointerEvent, useEffect, useRef } from 'react'
import { LEAVE_MS } from './motion'

export interface DragDismissHandlers {
  onPointerDown: (event: ReactPointerEvent<HTMLElement>) => void
  onPointerMove: (event: ReactPointerEvent<HTMLElement>) => void
  onPointerUp: (event: ReactPointerEvent<HTMLElement>) => void
  onPointerCancel: (event: ReactPointerEvent<HTMLElement>) => void
}

interface Drag {
  y: number
  t: number
  dy: number
}

/**
 * Drag the handle or the head of a bottom sheet down to close it: past a quarter of its height, or a flick of more
 * than 24px faster than 0.5px/ms. The body never starts a drag, so scrolling a long sheet stays possible. The panel
 * follows the finger and the scrim fades with it; a release that closes slides the rest of the way off before
 * `onClose` runs, and one that does not springs back.
 */
export function useDragDismiss(
  panelRef: React.RefObject<HTMLElement | null>,
  onClose: (() => void) | undefined,
  enabled: boolean,
  scrimRef?: React.RefObject<HTMLElement | null>,
): DragDismissHandlers {
  const drag = useRef<Drag | null>(null)
  const timers = useRef<Array<ReturnType<typeof setTimeout>>>([])

  useEffect(() => {
    const pending = timers.current
    return () => {
      for (const timer of pending) clearTimeout(timer)
    }
  }, [])

  const later = (run: () => void, ms: number) => {
    timers.current.push(setTimeout(run, ms))
  }

  const release = () => {
    const state = drag.current
    const panel = panelRef.current
    const scrim = scrimRef?.current
    drag.current = null
    if (!state || !panel) return
    const speed = state.dy / Math.max(1, Date.now() - state.t)
    const dismiss = state.dy > panel.offsetHeight * 0.25 || (speed > 0.5 && state.dy > 24)
    if (dismiss) {
      // The leave animation would start from the closed position and jump; the drag carries the panel off instead.
      panel.style.animation = 'none'
      panel.style.transition = `transform ${LEAVE_MS}ms var(--ease-in)`
      panel.style.transform = 'translateY(100%)'
      if (scrim) {
        scrim.style.transition = `opacity ${LEAVE_MS}ms var(--ease-in)`
        scrim.style.opacity = '0'
      }
      later(() => onClose?.(), LEAVE_MS)
      // A caller that keeps the sheet open (a guard) must not be left parked off screen.
      later(() => {
        if (!panel.isConnected) return
        panel.style.animation = ''
        panel.style.transform = ''
        panel.style.transition = ''
        if (scrim) {
          scrim.style.opacity = ''
          scrim.style.transition = ''
        }
      }, LEAVE_MS + 300)
      return
    }
    panel.style.transition = 'transform var(--duration-base) var(--ease-out)'
    panel.style.transform = ''
    if (scrim) {
      scrim.style.transition = 'opacity var(--duration-base) var(--ease-out)'
      scrim.style.opacity = ''
    }
    later(() => {
      panel.style.transition = ''
      if (scrim) scrim.style.transition = ''
    }, LEAVE_MS + 20)
  }

  return {
    onPointerDown(event) {
      if (!enabled || event.button > 0) return
      if ((event.target as HTMLElement).closest('button, a, input, select, textarea')) return
      drag.current = { y: event.clientY, t: Date.now(), dy: 0 }
      event.currentTarget.setPointerCapture(event.pointerId)
      // While the finger is on it, the panel follows without easing.
      if (panelRef.current) panelRef.current.style.transition = 'none'
      if (scrimRef?.current) scrimRef.current.style.transition = 'none'
    },
    onPointerMove(event) {
      const panel = panelRef.current
      if (!drag.current || !panel) return
      drag.current.dy = Math.max(0, event.clientY - drag.current.y)
      panel.style.transform = `translateY(${drag.current.dy}px)`
      if (scrimRef?.current) {
        scrimRef.current.style.opacity = String(Math.max(0, 1 - drag.current.dy / Math.max(1, panel.offsetHeight)))
      }
    },
    onPointerUp: release,
    onPointerCancel: release,
  }
}
