import type { ListSpec } from '@suite/remote-kit'
import { useEnumFacet } from '../../components/facets'
import { useT } from '../../i18n'

export function useCustomerListSpec(): ListSpec {
  const t = useT('customers')
  return {
    sortable: ['display_name', 'created_at', 'id'],
    searchField: 'display_name',
    searchOp: 'starts_with',
    searchPlaceholder: t('search'),
    facets: [useEnumFacet('status', 'status', ['ACTIVE', 'DEACTIVATED'])],
  }
}
