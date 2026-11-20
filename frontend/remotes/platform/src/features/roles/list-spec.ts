import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useRoleListSpec(): ListSpec {
  const t = useT('roles')
  const tc = useT('common')
  return {
    sortable: ['name', 'id'],
    searchField: 'name',
    searchOp: 'contains',
    searchPlaceholder: t('search'),
    facets: [
      {
        key: 'scope',
        label: tc('fields.scope'),
        field: 'scope',
        options: ['PLATFORM', 'TENANT'].map((value) => ({ value, label: tc(`scope.${value}`) })),
      },
    ],
  }
}
