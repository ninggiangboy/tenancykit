import { describe, expect, it, vi } from 'vitest'
import { createCommandRegistry } from './commands'

describe('the command registry', () => {
  it('adds a remote’s actions and takes them back on unmount', () => {
    const registry = createCommandRegistry()
    const listener = vi.fn()
    registry.subscribe(listener)

    const remove = registry.register([{ id: 'billing.new-invoice', label: 'New invoice', onSelect: () => {} }])
    expect(registry.items().map((item) => item.id)).toEqual(['billing.new-invoice'])
    expect(listener).toHaveBeenCalledTimes(1)

    remove()
    expect(registry.items()).toHaveLength(0)
    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('replaces an action registered under the same id', () => {
    const registry = createCommandRegistry()
    registry.register([{ id: 'open', label: 'Open', onSelect: () => {} }])
    registry.register([{ id: 'open', label: 'Open Billing', onSelect: () => {} }])

    expect(registry.items().map((item) => item.label)).toEqual(['Open Billing'])
  })

  it('notifies several subscribers', () => {
    const registry = createCommandRegistry()
    const first = vi.fn()
    const second = vi.fn()
    registry.subscribe(first)
    registry.subscribe(second)

    registry.register([{ id: 'a', label: 'A', onSelect: () => {} }])

    expect(first).toHaveBeenCalled()
    expect(second).toHaveBeenCalled()
  })
})
