import { Alert } from '@suite/ui'
import { useEffect } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'
import { errorMessage } from '../runtime/errors'

/** The mutation a dialog drives: what `useAction` returns, narrowed to what the dialog reads. */
export interface DialogAction {
  error: unknown
  isPending: boolean
  reset: () => void
}

/**
 * Clears the last failure whenever the dialog opens or closes, so a stale error never greets the next opening, and
 * returns the alert that shows the current one inside the dialog.
 */
export function useDialogAction(open: boolean, action: DialogAction | undefined) {
  const t = useKitT()
  const reset = action?.reset
  // A mutation's `reset` is stable, so only a change of `open` clears the last failure.
  useEffect(() => {
    reset?.()
  }, [open, reset])
  const failed = action?.error != null
  return {
    busy: action?.isPending === true,
    failure: failed ? (
      <Alert tone="danger" title={t('state.error')}>
        {errorMessage(action?.error, t('state.requestFailed'))}
      </Alert>
    ) : null,
  }
}
