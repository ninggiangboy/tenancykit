import { ErrorState } from '@suite/ui'
import { useKitT } from '../i18n/create-remote-i18n'
import { errorMessage, errorStatus } from '../runtime/errors'

/** A failed read: the server's own sentence when it sent one, the hint to retry, and the forbidden look for a 403. */
export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const t = useKitT()
  const forbidden = errorStatus(error) === 403
  const sentence = forbidden ? '' : errorMessage(error, '')
  return (
    <ErrorState
      title={t('state.readFailed')}
      description={sentence ? `${sentence} ${t('state.readFailedHint')}` : t('state.readFailedHint')}
      forbidden={forbidden}
      onRetry={onRetry}
    />
  )
}
