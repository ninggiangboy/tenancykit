import { listQuery, permissions } from '@suite/api-client'
import { invoiceCharge, markUncollectible, pay, voidInvoice } from '@suite/api-client/billing'
import { chargesOptions, invoice1Options, invoicesOptions } from '@suite/api-client/billing/query'
import { asListQuery, type EntityOptions, idempotencyKey, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { formatMoney } from '@suite/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useT } from '../../../i18n'

export function useInvoiceList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...invoicesOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    placeholderData: keepPreviousData,
    enabled: usePermission(permissions.billing.invoices),
  })
}

export function useInvoice(id: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...invoice1Options({ client: billing, throwOnError: true, path: { id: id ?? '' } }),
    enabled: Boolean(id),
  })
}

export function useInvoicePermissions() {
  return {
    create: usePermission(permissions.billing.invoiceCharge),
    manage: usePermission(permissions.billing.voidInvoice),
    pay: usePermission(permissions.billing.pay),
  }
}

/** Charges to invoice, newest first, searchable by reference. */
export function useChargeOptions(search: string): EntityOptions {
  const { billing } = useApi()
  const query = useQuery({
    ...chargesOptions({
      client: billing,
      throwOnError: true,
      query: asListQuery(
        listQuery({
          filters: search ? [{ field: 'reference', op: 'eq', value: search }] : [],
          sort: '-created_at',
          limit: 30,
        }),
      ),
    }),
    enabled: usePermission(permissions.billing.charges),
    placeholderData: keepPreviousData,
  })
  return {
    loading: query.isFetching,
    options: (query.data?.data ?? []).map((charge) => ({
      value: charge.id ?? '',
      label: `${formatMoney(charge.amount?.amount ?? 0, charge.amount?.currency)}${charge.reference ? ` · ${charge.reference}` : ''}`,
      hint: charge.id,
    })),
  }
}

export function useInvoiceCharge(onDone: () => void) {
  const { billing } = useApi()
  const tc = useT('common')
  return useAction((chargeId: string) => invoiceCharge({ client: billing, throwOnError: true, body: { chargeId } }), {
    onSuccess: onDone,
    successToast: tc('toast.created'),
  })
}

export function useVoidInvoice(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('invoices')
  return useAction(
    (vars: { id: string; reason?: string }) =>
      voidInvoice({ client: billing, throwOnError: true, path: { id: vars.id }, body: { reason: vars.reason ?? '' } }),
    { onSuccess: onDone, successToast: t('voided') },
  )
}

export function useMarkUncollectible(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('invoices')
  return useAction((id: string) => markUncollectible({ client: billing, throwOnError: true, path: { id } }), {
    onSuccess: onDone,
    successToast: t('uncollectibleDone'),
  })
}

export function useRecordPayment(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('invoices')
  return useAction(
    (id: string) => pay({ client: billing, throwOnError: true, path: { id }, headers: idempotencyKey() }),
    {
      onSuccess: onDone,
      successToast: t('paid'),
    },
  )
}
