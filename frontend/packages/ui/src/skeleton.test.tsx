import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Skeleton } from './skeleton'

describe('Skeleton', () => {
  it('moves its highlight with a transform on a child, not with the background', () => {
    const { container } = render(<Skeleton width={80} height={10} />)
    const block = container.firstElementChild as HTMLElement
    expect(block).toHaveClass('overflow-hidden')
    expect(block).not.toHaveClass('animate-shimmer')
    expect(block.firstElementChild).toHaveClass('animate-shimmer')
    expect(block.style.width).toBe('80px')
  })
})
