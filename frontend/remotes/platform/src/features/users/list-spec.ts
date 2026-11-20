import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useUserListSpec(): ListSpec {
  const t = useT('users')
  const tc = useT('common')
  return {
    sortable: ['id'],
    searchField: 'display_name',
    searchOp: 'starts_with',
    searchPlaceholder: t('search'),
    facets: [
      {
        key: 'status',
        label: tc('fields.status'),
        field: 'status',
        options: ['ACTIVE', 'DISABLED'].map((value) => ({ value, label: tc(`status.${value}`) })),
      },
      {
        key: 'scope',
        label: tc('fields.scope'),
        field: 'scope',
        options: ['PLATFORM', 'TENANT'].map((value) => ({ value, label: tc(`scope.${value}`) })),
      },
    ],
  }
}
