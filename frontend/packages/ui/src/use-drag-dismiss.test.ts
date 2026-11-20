import { fireEvent, render, screen } from '@testing-library/react'
import { createElement, useRef } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDragDismiss } from './use-drag-dismiss'

beforeAll(() => {
  HTMLElement.prototype.setPointerCapture = vi.fn()
  HTMLElement.prototype.releasePointerCapture = vi.fn()
})

function Panel({ onClose, enabled = true }: { onClose: () => void; enabled?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  const drag = useDragDismiss(ref, onClose, enabled)
  return createElement('div', { ref, 'data-testid': 'panel', ...drag }, 'drag me')
}

function panel(): HTMLElement {
  const element = screen.getByTestId('panel')
  Object.defineProperty(element, 'offsetHeight', { value: 800, configurable: true })
  return element
}

function drag(element: HTMLElement, from: number, to: number) {
  fireEvent.pointerDown(element, { clientY: from, pointerId: 1, button: 0 })
  fireEvent.pointerMove(element, { clientY: to, pointerId: 1 })
  fireEvent.pointerUp(element, { clientY: to, pointerId: 1 })
}

describe('useDragDismiss', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-20T09:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('closes after a drag past a quarter of the panel height', () => {
    const onClose = vi.fn()
    render(createElement(Panel, { onClose }))
    drag(panel(), 100, 400)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not close after a slow drag of 100px', () => {
    const onClose = vi.fn()
    render(createElement(Panel, { onClose }))
    const element = panel()
    fireEvent.pointerDown(element, { clientY: 100, pointerId: 1, button: 0 })
    vi.advanceTimersByTime(1000)
    fireEvent.pointerMove(element, { clientY: 200, pointerId: 1 })
    fireEvent.pointerUp(element, { clientY: 200, pointerId: 1 })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('does nothing while disabled', () => {
    const onClose = vi.fn()
    render(createElement(Panel, { onClose, enabled: false }))
    drag(panel(), 100, 400)
    expect(onClose).not.toHaveBeenCalled()
  })
})
