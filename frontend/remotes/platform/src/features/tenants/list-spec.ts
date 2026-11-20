import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useTenantListSpec(): ListSpec {
  const t = useT('tenants')
  const tc = useT('common')
  return {
    sortable: ['name', 'created_at', 'id'],
    searchField: 'code',
    searchOp: 'starts_with',
    searchPlaceholder: t('search'),
    facets: [
      {
        key: 'status',
        label: tc('fields.status'),
        field: 'status',
        options: ['ACTIVE', 'DEACTIVATED'].map((value) => ({ value, label: tc(`status.${value}`) })),
      },
    ],
  }
}
