import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DropdownMenu } from './dropdown-menu'

describe('DropdownMenu', () => {
  it('marks the current choice of an exclusive group with a trailing check and radio semantics', () => {
    render(
      <DropdownMenu
        defaultOpen
        items={[
          { label: 'Light', icon: 'sun', checked: false },
          { label: 'Dark', icon: 'moon', checked: true },
          { label: 'Rename', icon: 'pencil' },
        ]}
      />,
    )
    const light = screen.getByRole('menuitemradio', { name: 'Light' })
    const dark = screen.getByRole('menuitemradio', { name: 'Dark' })
    expect(light).toHaveAttribute('aria-checked', 'false')
    expect(dark).toHaveAttribute('aria-checked', 'true')
    expect(dark.querySelectorAll('svg')).toHaveLength(2)
    expect(light.querySelectorAll('svg')).toHaveLength(1)
    expect(screen.getByRole('menuitem', { name: 'Rename' })).not.toHaveAttribute('aria-checked')
  })
})
