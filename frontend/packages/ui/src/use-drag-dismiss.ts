import { type PointerEvent as ReactPointerEvent, useRef } from 'react'

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
 * than 24px faster than 0.5px/ms. The body never starts a drag, so scrolling a long sheet stays possible.
 */
export function useDragDismiss(
  panelRef: React.RefObject<HTMLElement | null>,
  onClose: (() => void) | undefined,
  enabled: boolean,
): DragDismissHandlers {
  const drag = useRef<Drag | null>(null)

  const release = () => {
    const state = drag.current
    const panel = panelRef.current
    drag.current = null
    if (!state || !panel) return
    const speed = state.dy / Math.max(1, Date.now() - state.t)
    if (state.dy > panel.offsetHeight * 0.25 || (speed > 0.5 && state.dy > 24)) {
      onClose?.()
    }
    panel.style.transition = 'transform var(--duration-base) var(--ease-out)'
    panel.style.transform = ''
    setTimeout(() => {
      panel.style.transition = ''
    }, 200)
  }

  return {
    onPointerDown(event) {
      if (!enabled || event.button > 0) return
      if ((event.target as HTMLElement).closest('button, a, input, select, textarea')) return
      drag.current = { y: event.clientY, t: Date.now(), dy: 0 }
      event.currentTarget.setPointerCapture(event.pointerId)
    },
    onPointerMove(event) {
      if (!drag.current || !panelRef.current) return
      drag.current.dy = Math.max(0, event.clientY - drag.current.y)
      panelRef.current.style.transform = `translateY(${drag.current.dy}px)`
    },
    onPointerUp: release,
    onPointerCancel: release,
  }
}
