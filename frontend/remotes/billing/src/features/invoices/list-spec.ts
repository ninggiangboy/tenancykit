import type { ListSpec } from '@suite/remote-kit'
import { useT } from '../../i18n'

export function useInvoiceListSpec(): ListSpec {
  const t = useT('invoices')
  const status = (value: string) => [{ field: 'status', op: 'eq' as const, value }]
  return {
    sortable: ['id', 'created_at', 'total'],
    searchField: 'number',
    searchOp: 'starts_with',
    searchPlaceholder: t('search'),
    views: [
      { id: 'all', label: t('viewAll') },
      { id: 'open', label: t('viewOpen'), filters: status('OPEN') },
      { id: 'paid', label: t('viewPaid'), filters: status('PAID') },
      { id: 'void', label: t('viewVoid'), filters: status('VOID') },
    ],
  }
}
