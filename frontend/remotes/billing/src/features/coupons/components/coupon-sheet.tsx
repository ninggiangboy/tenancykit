import type { CouponResponse } from '@suite/api-client/billing'
import { ConfirmDialog, DetailRow, DetailRows, Maybe, RecordSheet, Timestamp } from '@suite/remote-kit'
import { Button, Checkbox, CopyId, PermissionGate, StatusBadge } from '@suite/ui'
import { useId, useState } from 'react'
import { useT } from '../../../i18n'
import { useCanManageCoupons, useDeactivateCoupon } from '../hooks/use-coupons'
import { CouponBenefit } from './coupon-benefit'
import { CouponCodes } from './coupon-codes'

export interface CouponSheetProps {
  coupon: CouponResponse
  onClose: () => void
  onPrev?: () => void
  onNext?: () => void
}

export function CouponSheet({ coupon, onClose, onPrev, onNext }: CouponSheetProps) {
  const t = useT('coupons')
  const tc = useT('common')
  const canManage = useCanManageCoupons()
  const [deactivating, setDeactivating] = useState(false)
  const [revoke, setRevoke] = useState(false)
  const revokeId = useId()
  const deactivate = useDeactivateCoupon(() => setDeactivating(false))

  return (
    <>
      <RecordSheet
        open
        onClose={onClose}
        eyebrow={t('title')}
        title={coupon.name ?? ''}
        description={<CouponBenefit coupon={coupon} />}
        onPrev={onPrev}
        onNext={onNext}
        actions={
          <PermissionGate allowed={canManage && coupon.status !== 'DEACTIVATED'}>
            <Button variant="danger" icon="ban" onClick={() => setDeactivating(true)}>
              {t('deactivate')}
            </Button>
          </PermissionGate>
        }
      >
        <div className="flex flex-col gap-5">
          <DetailRows>
            <DetailRow label={tc('fields.status')}>
              <StatusBadge status={coupon.status} />
            </DetailRow>
            <DetailRow label={t('redemptions')}>
              {coupon.redemptionsCount ?? 0}/{coupon.maxRedemptions ?? t('unlimited')}
            </DetailRow>
            <DetailRow label={t('maxPerCustomer')}>
              <Maybe value={coupon.maxPerCustomer} />
            </DetailRow>
            <DetailRow label={t('redeemBy')}>
              <Timestamp value={coupon.redeemBy} />
            </DetailRow>
            <DetailRow label={t('stackable')}>{coupon.stackable ? tc('state.on') : tc('state.off')}</DetailRow>
            <DetailRow label={tc('fields.created')}>
              <Timestamp value={coupon.createdAt} />
            </DetailRow>
            <DetailRow label={tc('fields.id')}>
              <CopyId value={coupon.id ?? ''} />
            </DetailRow>
          </DetailRows>
          <CouponCodes coupon={coupon} canManage={canManage} />
        </div>
      </RecordSheet>
      <ConfirmDialog
        open={deactivating}
        onClose={() => setDeactivating(false)}
        title={t('deactivate')}
        description={tc('confirm.irreversible')}
        confirmLabel={t('deactivate')}
        busy={deactivate.isPending}
        onConfirm={() => deactivate.mutate({ id: coupon.id ?? '', revokeRedemptions: revoke })}
        impact={[
          { label: tc('fields.name'), value: coupon.name ?? '' },
          { label: t('redemptions'), value: coupon.redemptionsCount ?? 0 },
        ]}
      >
        <div className="flex items-start gap-2 text-small">
          <Checkbox id={revokeId} checked={revoke} onCheckedChange={(checked) => setRevoke(checked === true)} />
          <label htmlFor={revokeId} className="flex flex-col">
            <span className="text-body-strong text-foreground">{t('revokeRedemptions')}</span>
            <span className="text-muted-foreground">{t('revokeHint')}</span>
          </label>
        </div>
      </ConfirmDialog>
    </>
  )
}
