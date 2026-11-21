import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FacetedFilter } from './faceted-filter'

describe('FacetedFilter', () => {
  it('toggles an option from its text, its count and its box, once each', async () => {
    const onChange = vi.fn()
    render(
      <FacetedFilter
        label="Status"
        defaultOpen
        selected={[]}
        onChange={onChange}
        options={[{ value: 'ACTIVE', label: 'Active', count: 7 }]}
      />,
    )
    await userEvent.click(screen.getByText('Active'))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenLastCalledWith(['ACTIVE'])

    await userEvent.click(screen.getByText('7'))
    expect(onChange).toHaveBeenCalledTimes(2)

    await userEvent.click(screen.getByRole('checkbox', { name: 'Active' }))
    expect(onChange).toHaveBeenCalledTimes(3)
  })
})
