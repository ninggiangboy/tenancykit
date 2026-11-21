import { ApiError } from '@suite/api-client'
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderKit } from '../test-utils'
import { ErrorPanel } from './error-panel'

describe('ErrorPanel', () => {
  it('says what the server said', () => {
    renderKit(<ErrorPanel error={new ApiError(500, { title: 'Boom', detail: 'Database is down' }, 'x')} />)
    expect(screen.getByText(/Database is down/)).toBeInTheDocument()
    expect(screen.getByText('Could not load this.')).toBeInTheDocument()
  })

  it('keeps the hint alone when the error carries no sentence', () => {
    renderKit(<ErrorPanel error={undefined} />)
    expect(screen.getByText('Retry. If it keeps failing, reload the page.')).toBeInTheDocument()
  })

  it('keeps the forbidden look for a 403', () => {
    renderKit(<ErrorPanel error={new ApiError(403, { title: 'Forbidden', detail: 'No' }, 'x')} />)
    expect(screen.queryByText(/No\b/)).toBeNull()
    expect(screen.queryByRole('button', { name: /retry/i })).toBeNull()
  })
})
