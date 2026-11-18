import { describe, expect, it, vi } from 'vitest'
import { listParam, numberParam, toggleValue, urlState } from './url-state'

describe('url state', () => {
  it('reads a value and a comma list', () => {
    const state = urlState({ tab: 'open', status: ['PAID', 'OPEN'] }, () => {})

    expect(state.get('tab')).toBe('open')
    expect(state.get('status')).toBe('PAID,OPEN')
    expect(listParam(state.get('status'))).toEqual(['PAID', 'OPEN'])
    expect(state.get('missing')).toBeNull()
  })

  it('writes one key and leaves the others alone', () => {
    const navigate = vi.fn()
    const state = urlState({ tab: 'open', page: '2' }, navigate)

    state.set({ tab: 'paid', page: null, sort: '-created_at' })

    expect(navigate).toHaveBeenCalledWith({ tab: 'paid', sort: '-created_at' })
  })

  it('falls back rather than producing NaN', () => {
    expect(numberParam('25', 50)).toBe(25)
    expect(numberParam(null, 50)).toBe(50)
    expect(numberParam('many', 50)).toBe(50)
    expect(numberParam('0', 50)).toBe(50)
  })

  it('toggles one value of a multi-select', () => {
    expect(toggleValue(['A'], 'B')).toEqual(['A', 'B'])
    expect(toggleValue(['A', 'B'], 'A')).toEqual(['B'])
  })
})
