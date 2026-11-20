import { permissions } from '@suite/api-client'
import { openWallet, reverse } from '@suite/api-client/billing'
import { ledger1Options, walletsOptions } from '@suite/api-client/billing/query'
import { asListQuery, type ListState, useAction, useApi } from '@suite/remote-kit'
import { usePermission } from '@suite/shell-sdk/react'
import { useQuery } from '@tanstack/react-query'
import { useT } from '../../i18n'

export function useWalletList(list: ListState) {
  const { billing } = useApi()
  return useQuery({
    ...walletsOptions({ client: billing, throwOnError: true, query: asListQuery(list.params) }),
    enabled: usePermission(permissions.billing.wallets),
  })
}

export function useLedger(walletId: string | undefined) {
  const { billing } = useApi()
  return useQuery({
    ...ledger1Options({ client: billing, throwOnError: true, path: { id: walletId ?? '' } }),
    enabled: Boolean(walletId),
  })
}

export function useWalletPermissions() {
  return { open: usePermission(permissions.billing.openWallet), reverse: usePermission(permissions.billing.reverse) }
}

export function useOpenWallet(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('wallets')
  return useAction(
    (body: { customerId: string; currency: string; mode: string; creditLimit?: number }) =>
      openWallet({ client: billing, throwOnError: true, body }),
    { onSuccess: onDone, successToast: t('created') },
  )
}

export function useReverseEntry(onDone: () => void) {
  const { billing } = useApi()
  const t = useT('wallets')
  return useAction(
    (vars: { id: string; reason: string }) =>
      reverse({ client: billing, throwOnError: true, path: { id: vars.id }, body: { reason: vars.reason } }),
    { onSuccess: onDone, successToast: t('reversed') },
  )
}
