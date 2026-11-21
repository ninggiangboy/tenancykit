import { permissions } from '@suite/api-client'
import type { UserResponse } from '@suite/api-client/platform'
import { Section } from '@suite/remote-kit'
import { Button, Combobox } from '@suite/ui'
import { useState } from 'react'
import { useT } from '../../../i18n'
import { useSetUserPermission } from '../hooks/use-users'

const KNOWN_CODES = [
  ...new Set(Object.values(permissions).flatMap((group) => Object.values(group as Record<string, string>))),
]
  .sort()
  .map((code) => ({ value: code, label: code }))

/** Grants or revokes one code on top of the user's roles; the API keeps no list to show, so this is a command. */
export function DirectPermissions({ user }: { user: UserResponse }) {
  const t = useT('users')
  const [code, setCode] = useState<string | null>(null)
  const setPermission = useSetUserPermission()
  const run = (grant: boolean) => {
    if (!code) return
    setPermission.mutate({ user, code, grant }, { onSuccess: () => setCode(null) })
  }
  return (
    <Section title={t('permissionsTitle')} description={t('permissionsDescription')}>
      <Combobox
        label={t('code')}
        value={code}
        onChange={setCode}
        options={KNOWN_CODES}
        creatable
        placeholder="billing:invoice:read"
      />
      <div className="flex gap-2">
        <Button
          size="sm"
          icon="check"
          disabled={!code}
          loading={setPermission.isPending && setPermission.variables?.grant === true}
          onClick={() => run(true)}
        >
          {t('grant')}
        </Button>
        <Button
          size="sm"
          variant="outline"
          icon="x"
          disabled={!code || setPermission.isPending}
          loading={setPermission.isPending && setPermission.variables?.grant === false}
          onClick={() => run(false)}
        >
          {t('revoke')}
        </Button>
      </div>
    </Section>
  )
}
