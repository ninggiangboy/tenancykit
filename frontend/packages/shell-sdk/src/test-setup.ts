import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Without Vitest globals, Testing Library cannot register its own cleanup; every test leaves its page behind otherwise.
afterEach(cleanup)

// jsdom lacks these, and the Radix and cmdk primitives ask for them.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, 'ResizeObserver', { value: ResizeObserverStub, configurable: true })
Object.defineProperty(Element.prototype, 'scrollIntoView', { value: () => {}, configurable: true })
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})
