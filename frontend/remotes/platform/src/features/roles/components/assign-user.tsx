import { Section } from '@suite/remote-kit'
import { Button, Combobox } from '@suite/ui'
import { useEffect, useState } from 'react'
import { useUserOptions } from '../../../components/tenant-lookup'
import { useT } from '../../../i18n'
import { useAssignRole } from '../hooks/use-roles'

export function AssignUser({ roleId }: { roleId: string }) {
  const t = useT('roles')
  const [userId, setUserId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => setSearch(query), 250)
    return () => clearTimeout(timer)
  }, [query])
  const { options, loading } = useUserOptions(search)
  const assign = useAssignRole()
  const run = (on: boolean) => {
    if (!userId) return
    assign.mutate({ roleId, userId, assign: on }, { onSuccess: () => setUserId(null) })
  }
  return (
    <Section title={t('assignTitle')} description={t('assignHint')}>
      <Combobox
        label={t('userPicker')}
        value={userId}
        onChange={setUserId}
        options={options}
        loading={loading}
        onSearch={setQuery}
      />
      <div className="flex gap-2">
        <Button size="sm" icon="plus" disabled={!userId} loading={assign.isPending} onClick={() => run(true)}>
          {t('assignUser')}
        </Button>
        <Button size="sm" variant="outline" icon="x" disabled={!userId || assign.isPending} onClick={() => run(false)}>
          {t('unassignUser')}
        </Button>
      </div>
    </Section>
  )
}
