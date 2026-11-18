import { describe, expect, it } from 'vitest'
import { defineRemote, isCompatible, SDK_MAJOR } from './index'

const Root = () => null

describe('shell contract', () => {
  it('accepts a remote built against the same major', () => {
    expect(isCompatible(SDK_MAJOR)).toBe(true)
    expect(isCompatible(SDK_MAJOR + 1)).toBe(false)
  })

  it('types the module a remote exports', () => {
    const module = defineRemote({ name: 'billing', sdkMajor: SDK_MAJOR, Root })
    expect(module.name).toBe('billing')
  })
})
