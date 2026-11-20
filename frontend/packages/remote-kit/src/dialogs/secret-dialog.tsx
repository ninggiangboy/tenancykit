import { Button, Dialog } from '@suite/ui'
import { useKitT } from '../i18n/create-remote-i18n'
import { useToast } from '../runtime/remote-provider'

export interface SecretDialogProps {
  open: boolean
  onClose: () => void
  secret?: string | null
  title?: string
  description?: string
}

/** A key or signing secret shown exactly once, never put in the query cache, the URL or a log. */
export function SecretDialog({ open, onClose, secret, title, description }: SecretDialogProps) {
  const t = useKitT()
  const toast = useToast()
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title ?? t('secret.title')}
      description={description ?? t('secret.hint')}
      footer={
        <>
          <Button
            variant="outline"
            size="sm"
            icon="copy"
            onClick={() => {
              if (!secret) return
              void navigator.clipboard?.writeText(secret)
              toast({ title: t('actions.copied'), tone: 'success' })
            }}
          >
            {t('secret.copy')}
          </Button>
          <Button size="sm" onClick={onClose}>
            {t('secret.done')}
          </Button>
        </>
      }
    >
      <code className="block break-all rounded-md border border-border bg-muted p-3 font-mono text-code text-foreground">
        {secret ?? ''}
      </code>
    </Dialog>
  )
}
