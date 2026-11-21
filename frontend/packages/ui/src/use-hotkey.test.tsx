import { renderHook } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { useHotkey } from './command-palette'

describe('useHotkey', () => {
  it('keeps one listener across renders and calls the latest handler', async () => {
    const add = vi.spyOn(document, 'addEventListener')
    const first = vi.fn()
    const latest = vi.fn()
    const { rerender } = renderHook(({ handler }) => useHotkey('/', handler), { initialProps: { handler: first } })
    for (let renders = 0; renders < 10; renders++) rerender({ handler: renders === 9 ? latest : vi.fn() })
    expect(add.mock.calls.filter(([type]) => type === 'keydown')).toHaveLength(1)

    await userEvent.keyboard('/')
    expect(latest).toHaveBeenCalledTimes(1)
    expect(first).not.toHaveBeenCalled()
  })
})
