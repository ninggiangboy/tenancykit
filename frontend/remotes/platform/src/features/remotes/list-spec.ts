import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useRemoteListSpec(): ListSpec {
  const t = useT('remotes')
  return {
    sortable: ['name'],
    searchField: 'name',
    searchOp: 'contains',
    searchPlaceholder: t('search'),
    views: [
      { id: 'all', label: t('viewAll') },
      { id: 'enabled', label: t('viewEnabled'), filters: [{ field: 'enabled', op: 'eq', value: true }] },
      { id: 'disabled', label: t('viewDisabled'), filters: [{ field: 'enabled', op: 'eq', value: false }] },
    ],
  }
}
