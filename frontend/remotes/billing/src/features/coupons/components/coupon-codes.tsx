import type { CouponResponse } from '@suite/api-client/billing'
import { FormField, QueryState, Section, useZodForm } from '@suite/remote-kit'
import { Button, PermissionGate, StatusBadge } from '@suite/ui'
import { useT } from '../../../i18n'
import { useAddCode, useCouponCodes, useDeactivateCode } from '../hooks/use-coupons'
import { addCodeSchema } from '../schema'

export function CouponCodes({ coupon, canManage }: { coupon: CouponResponse; canManage: boolean }) {
  const t = useT('coupons')
  const tc = useT('common')
  const codes = useCouponCodes(coupon.id)
  const deactivate = useDeactivateCode()
  const form = useZodForm(addCodeSchema, { code: '', maxRedemptions: '' })
  const add = useAddCode(coupon.id ?? '', () => form.reset())

  return (
    <Section title={t('codes')}>
      <QueryState
        query={codes}
        skeletonHeight={40}
        isEmpty={(data) => (data.data ?? []).length === 0}
        empty={<p className="text-small text-muted-foreground">{tc('state.none')}</p>}
      >
        {(data) => (
          <ul className="divide-y divide-border rounded-lg border border-border">
            {(data.data ?? []).map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-2 px-3 py-2 text-small">
                <span className="flex items-center gap-2">
                  <span className="font-mono text-code">{entry.code}</span>
                  <StatusBadge tone={entry.active ? 'success' : 'neutral'}>
                    {entry.active ? tc('state.on') : tc('state.off')}
                  </StatusBadge>
                </span>
                <span className="flex items-center gap-2">
                  <span className="text-caption tabular-nums text-muted-foreground">
                    {entry.redeemedCount ?? 0}/{entry.maxRedemptions ?? t('unlimited')}
                  </span>
                  <PermissionGate allowed={canManage && entry.active === true}>
                    <Button variant="ghost" size="sm" onClick={() => entry.id && deactivate.mutate(entry.id)}>
                      {t('deactivateCode')}
                    </Button>
                  </PermissionGate>
                </span>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
      <PermissionGate allowed={canManage && coupon.status !== 'DEACTIVATED'}>
        <form
          noValidate
          className="grid grid-cols-[1fr_8rem_auto] items-end gap-2"
          onSubmit={(event) => void form.handleSubmit((values) => add.mutate(values))(event)}
        >
          <FormField control={form.control} name="code" label={t('code')} mono />
          <FormField
            control={form.control}
            name="maxRedemptions"
            label={t('maxRedemptions')}
            type="number"
            min={1}
            optional
            hideLabel
          />
          <Button type="submit" size="md" icon="plus" loading={add.isPending}>
            {t('addCode')}
          </Button>
        </form>
      </PermissionGate>
    </Section>
  )
}
