/**
 * Every service answers a failure with RFC 9457: `{type, title, status, detail}`. This reads that body once and keeps
 * the status, so a screen shows the server's own sentence instead of a status number.
 */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string
  readonly problem: ProblemDetail | null

  constructor(status: number, problem: ProblemDetail | null, fallback: string) {
    const detail = problem?.detail ?? problem?.title ?? fallback
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.title = problem?.title ?? ''
    this.detail = typeof detail === 'string' ? detail : fallback
    this.problem = problem
  }

  static async fromResponse(response: Response): Promise<ApiError> {
    const fallback = `The request failed with ${response.status}.`
    try {
      const body = (await response.json()) as ProblemDetail
      return new ApiError(response.status, body, fallback)
    } catch {
      return new ApiError(response.status, null, fallback)
    }
  }

  static is(error: unknown): error is ApiError {
    return error instanceof ApiError
  }

  /** A stale If-Match: the caller fetches the record again and retries. */
  get isPreconditionFailed(): boolean {
    return this.status === 412
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

/** The `If-Match` a conditional update carries, built from the version the caller read. */
export function etag(version: number): string {
  return `"${version}"`
}
