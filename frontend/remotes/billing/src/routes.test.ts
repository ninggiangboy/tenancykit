import { Icon } from '@suite/ui'
import { render } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { screens } from './routes'

describe('billing navigation icons', () => {
  it('gives every screen an icon of its own', () => {
    const icons = screens.map((screen) => screen.icon)
    expect(icons.every(Boolean)).toBe(true)
    expect(new Set(icons).size).toBe(icons.length)
  })

  it('names glyphs the design system draws', () => {
    for (const screen of screens) {
      const { container, unmount } = render(createElement(Icon, { name: screen.icon ?? 'file' }))
      expect(container.querySelector('svg'), screen.id).not.toBeNull()
      unmount()
    }
  })
})
