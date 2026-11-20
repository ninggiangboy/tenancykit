import { ErrorState } from '@suite/ui'
import { useKitT } from '../i18n/create-remote-i18n'
import { errorStatus } from '../runtime/errors'

/** A failed read: the server's own sentence, a retry, and the forbidden look for a 403. */
export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const t = useKitT()
  return (
    <ErrorState
      title={t('state.readFailed')}
      description={t('state.readFailedHint')}
      forbidden={errorStatus(error) === 403}
      onRetry={onRetry}
    />
  )
}
