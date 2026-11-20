import { Button } from '@suite/ui'
import { useKitT } from '../i18n/create-remote-i18n'
import { useToast } from '../runtime/remote-provider'

/** The bulk action every list has: the selected rows' identifiers on the clipboard, one per line. */
export function BulkCopyIds({ ids }: { ids: string[] }) {
  const t = useKitT()
  const toast = useToast()
  return (
    <Button
      variant="ghost"
      size="sm"
      icon="copy"
      onClick={() => {
        void navigator.clipboard?.writeText(ids.join('\n'))
        toast({ title: t('actions.copied'), tone: 'success' })
      }}
    >
      {t('actions.copyIds')}
    </Button>
  )
}
