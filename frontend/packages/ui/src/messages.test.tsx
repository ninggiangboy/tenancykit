import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { en, UiMessagesProvider, useUiMessages, vi } from './messages'

function Word({ message }: { message: Parameters<ReturnType<typeof useUiMessages>>[0] }) {
  const t = useUiMessages()
  return <p>{t(message, { count: 3 })}</p>
}

describe('the built-in catalogs', () => {
  it('ships the same keys in both languages', () => {
    expect(Object.keys(vi).sort()).toEqual(Object.keys(en).sort())
  })

  it('translates and interpolates', () => {
    render(
      <UiMessagesProvider locale="vi">
        <Word message="table.selected" />
      </UiMessagesProvider>,
    )

    expect(screen.getByText('Đã chọn 3')).toBeInTheDocument()
  })

  it('falls back to English outside a provider', () => {
    render(<Word message="table.selected" />)

    expect(screen.getByText('3 selected')).toBeInTheDocument()
  })
})
