import type { ListSpec } from '@suite/remote-kit'
import { useEnumFacet } from '../../components/facets'

export function usePaymentListSpec(): ListSpec {
  return {
    sortable: ['amount', 'created_at', 'id'],
    facets: [useEnumFacet('status', 'status', ['PENDING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'REFUNDED'])],
  }
}
