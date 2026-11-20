import { ApiError } from '@suite/api-client'

/** The server's own sentence where it sent one; the status object hey-api throws is not an Error. */
export function errorMessage(error: unknown, fallback = 'The request failed.'): string {
  if (error instanceof ApiError) {
    return error.detail
  }
  if (error && typeof error === 'object') {
    const problem = error as { detail?: unknown; title?: unknown; message?: unknown }
    if (typeof problem.detail === 'string' && problem.detail) return problem.detail
    if (typeof problem.title === 'string' && problem.title) return problem.title
    if (typeof problem.message === 'string' && problem.message) return problem.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return fallback
}

export function errorStatus(error: unknown): number | undefined {
  if (error instanceof ApiError) return error.status
  if (error && typeof error === 'object' && typeof (error as { status?: unknown }).status === 'number') {
    return (error as { status: number }).status
  }
  return undefined
}
