import { Button, Card, PermissionGate, Skeleton, TagInput } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useT } from '../../../i18n'
import { type StripeActions, useReturnUrls } from '../hooks'

/** Edited as chips and saved as one replace; the save button only lights up when something changed. */
export function ReturnUrls({ canManage, actions }: { canManage: boolean; actions: StripeActions }) {
  const t = useT('stripe')
  const query = useReturnUrls()
  const saved = query.data ?? []
  const [urls, setUrls] = useState<string[]>([])
  const [error, setError] = useState<string | undefined>()
  useEffect(() => {
    setUrls(query.data ?? [])
  }, [query.data])
  const changed = urls.join('\n') !== saved.join('\n')

  return (
    <Card
      title={t('returnUrls')}
      action={
        <PermissionGate allowed={canManage}>
          <Button
            size="sm"
            disabled={!changed}
            loading={actions.returnUrls.isPending}
            onClick={() => {
              const bad = urls.find((url) => !/^https:\/\/[^\s]+$/.test(url))
              setError(bad ? `${bad}: https://…` : undefined)
              if (!bad) actions.returnUrls.mutate(urls)
            }}
          >
            {t('replaceUrls')}
          </Button>
        </PermissionGate>
      }
    >
      {query.isLoading ? (
        <Skeleton width="100%" height={40} />
      ) : (
        <TagInput
          label={t('returnUrls')}
          hint={t('urlsHint')}
          error={error}
          value={urls}
          onChange={canManage ? setUrls : () => {}}
          placeholder={t('urlPlaceholder')}
        />
      )}
    </Card>
  )
}
