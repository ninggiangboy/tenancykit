import { describe, expect, it } from 'vitest'
import { ApiError, etag } from './errors'

describe('ApiError', () => {
  it('keeps the status and the server’s own detail', async () => {
    const response = new Response(
      JSON.stringify({
        type: 'about:blank',
        title: 'Precondition Failed',
        status: 412,
        detail: 'The resource changed since it was read.',
      }),
      { status: 412, headers: { 'content-type': 'application/problem+json' } },
    )

    const error = await ApiError.fromResponse(response)

    expect(error.status).toBe(412)
    expect(error.title).toBe('Precondition Failed')
    expect(error.detail).toBe('The resource changed since it was read.')
    expect(error.isPreconditionFailed).toBe(true)
    expect(error.isForbidden).toBe(false)
  })

  it('falls back when the body is not a problem document', async () => {
    const error = await ApiError.fromResponse(new Response('not json', { status: 502 }))

    expect(error.status).toBe(502)
    expect(error.problem).toBeNull()
    expect(error.detail).toContain('502')
  })

  it('builds the strong tag a conditional update sends', () => {
    expect(etag(3)).toBe('"3"')
  })
})
