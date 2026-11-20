import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useSystemListSpec(): ListSpec {
  const t = useT('systems')
  const tc = useT('common')
  return {
    sortable: ['name', 'created_at', 'id'],
    searchField: 'name',
    searchOp: 'contains',
    searchPlaceholder: t('search'),
    facets: [
      {
        key: 'status',
        label: tc('fields.status'),
        field: 'status',
        options: ['ACTIVE', 'DISABLED'].map((value) => ({ value, label: tc(`status.${value}`) })),
      },
    ],
  }
}
