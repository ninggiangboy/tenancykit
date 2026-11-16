package dev.ngb.billing.coupon.adapter.redemption.persistence;

import static dev.ngb.billing_jooq.Tables.COUPON_REDEMPTIONS;

import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.coupon.domain.redemption.EndReason;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.coupon.domain.redemption.RedemptionStatus;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing_jooq.tables.records.CouponRedemptionsRecord;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class JooqRedemptionRepository implements RedemptionRepository {

    private final DSLContext dsl;

    public JooqRedemptionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Redemption redemption) {
        dsl.insertInto(COUPON_REDEMPTIONS)
                .set(COUPON_REDEMPTIONS.ID, redemption.id().value())
                .set(COUPON_REDEMPTIONS.TENANT_ID, redemption.tenantId().value())
                .set(COUPON_REDEMPTIONS.COUPON_ID, redemption.couponId().value())
                .set(COUPON_REDEMPTIONS.CODE_ID, redemption.codeId() == null ? null : redemption.codeId().value())
                .set(COUPON_REDEMPTIONS.WALLET_ID, redemption.walletId().value())
                .set(COUPON_REDEMPTIONS.CUSTOMER_ID, redemption.customerId().value())
                .set(COUPON_REDEMPTIONS.CURRENCY, redemption.currency().code())
                .set(COUPON_REDEMPTIONS.STATUS, redemption.status().name())
                .set(COUPON_REDEMPTIONS.MAX_APPLICATIONS, redemption.maxApplications())
                .set(COUPON_REDEMPTIONS.MAX_TOTAL_DISCOUNT, redemption.maxTotalDiscount() == null ? null : redemption.maxTotalDiscount().minorUnits())
                .set(COUPON_REDEMPTIONS.APPLICATIONS_USED, redemption.applicationsUsed())
                .set(COUPON_REDEMPTIONS.DISCOUNT_TOTAL, redemption.discountTotal().minorUnits())
                .set(COUPON_REDEMPTIONS.REDEEMED_AT, redemption.redeemedAt())
                .set(COUPON_REDEMPTIONS.ENDS_AT, redemption.endsAt())
                .set(COUPON_REDEMPTIONS.END_REASON, redemption.endReason() == null ? null : redemption.endReason().name())
                .set(COUPON_REDEMPTIONS.ENDED_AT, redemption.endedAt())
                .set(COUPON_REDEMPTIONS.ACTOR, redemption.actor())
                .set(COUPON_REDEMPTIONS.IDEMPOTENCY_KEY, redemption.idempotencyKey())
                .set(COUPON_REDEMPTIONS.REQUEST_HASH, redemption.requestHash())
                .execute();
    }

    @Override
    public Optional<Redemption> findById(RedemptionId id) {
        return dsl.selectFrom(COUPON_REDEMPTIONS).where(COUPON_REDEMPTIONS.ID.eq(id.value())).fetchOptional(JooqRedemptionRepository::toDomain);
    }

    @Override
    public Optional<Redemption> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(COUPON_REDEMPTIONS)
                .where(COUPON_REDEMPTIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(JooqRedemptionRepository::toDomain);
    }

    @Override
    public List<Redemption> lockActiveByWallet(WalletId walletId) {
        return dsl.selectFrom(COUPON_REDEMPTIONS)
                .where(COUPON_REDEMPTIONS.WALLET_ID.eq(walletId.value()))
                .and(COUPON_REDEMPTIONS.STATUS.eq(RedemptionStatus.ACTIVE.name()))
                .orderBy(COUPON_REDEMPTIONS.ID)
                .forUpdate()
                .fetch(JooqRedemptionRepository::toDomain);
    }

    @Override
    public List<Redemption> lockActiveByWalletAndCoupon(WalletId walletId, CouponId couponId) {
        return dsl.selectFrom(COUPON_REDEMPTIONS)
                .where(COUPON_REDEMPTIONS.WALLET_ID.eq(walletId.value()))
                .and(COUPON_REDEMPTIONS.COUPON_ID.eq(couponId.value()))
                .and(COUPON_REDEMPTIONS.STATUS.eq(RedemptionStatus.ACTIVE.name()))
                .orderBy(COUPON_REDEMPTIONS.ID)
                .forUpdate()
                .fetch(JooqRedemptionRepository::toDomain);
    }

    @Override
    public List<Redemption> lockActiveByCoupon(CouponId couponId) {
        return dsl.selectFrom(COUPON_REDEMPTIONS)
                .where(COUPON_REDEMPTIONS.COUPON_ID.eq(couponId.value()))
                .and(COUPON_REDEMPTIONS.STATUS.eq(RedemptionStatus.ACTIVE.name()))
                .orderBy(COUPON_REDEMPTIONS.ID)
                .forUpdate()
                .fetch(JooqRedemptionRepository::toDomain);
    }

    @Override
    public long countForCustomer(CustomerId customerId, CouponId couponId) {
        return dsl.fetchCount(COUPON_REDEMPTIONS,
                COUPON_REDEMPTIONS.CUSTOMER_ID.eq(customerId.value())
                        .and(COUPON_REDEMPTIONS.COUPON_ID.eq(couponId.value()))
                        .and(COUPON_REDEMPTIONS.STATUS.ne(RedemptionStatus.REVOKED.name())));
    }

    @Override
    public void update(Redemption redemption) {
        dsl.update(COUPON_REDEMPTIONS)
                .set(COUPON_REDEMPTIONS.STATUS, redemption.status().name())
                .set(COUPON_REDEMPTIONS.APPLICATIONS_USED, redemption.applicationsUsed())
                .set(COUPON_REDEMPTIONS.DISCOUNT_TOTAL, redemption.discountTotal().minorUnits())
                .set(COUPON_REDEMPTIONS.END_REASON, redemption.endReason() == null ? null : redemption.endReason().name())
                .set(COUPON_REDEMPTIONS.ENDED_AT, redemption.endedAt())
                .where(COUPON_REDEMPTIONS.ID.eq(redemption.id().value()))
                .execute();
    }

    @Override
    public boolean recordApplication(RedemptionId id, long taken, Instant now) {
        return dsl.update(COUPON_REDEMPTIONS)
                .set(COUPON_REDEMPTIONS.APPLICATIONS_USED, COUPON_REDEMPTIONS.APPLICATIONS_USED.plus(1))
                .set(COUPON_REDEMPTIONS.DISCOUNT_TOTAL, COUPON_REDEMPTIONS.DISCOUNT_TOTAL.plus(taken))
                .where(COUPON_REDEMPTIONS.ID.eq(id.value()))
                .and(COUPON_REDEMPTIONS.STATUS.eq(RedemptionStatus.ACTIVE.name()))
                .and(DSL.or(COUPON_REDEMPTIONS.ENDS_AT.isNull(), COUPON_REDEMPTIONS.ENDS_AT.gt(now)))
                .and(DSL.or(COUPON_REDEMPTIONS.MAX_TOTAL_DISCOUNT.isNull(),
                        COUPON_REDEMPTIONS.DISCOUNT_TOTAL.plus(taken).le(COUPON_REDEMPTIONS.MAX_TOTAL_DISCOUNT)))
                .execute() == 1;
    }

    private static Redemption toDomain(CouponRedemptionsRecord row) {
        Currency currency = Currency.of(row.getCurrency().strip());
        return Redemption.restore(
                new RedemptionId(row.getId()),
                new TenantId(row.getTenantId()),
                new CouponId(row.getCouponId()),
                row.getCodeId() == null ? null : new PromotionCodeId(row.getCodeId()),
                new WalletId(row.getWalletId()),
                new CustomerId(row.getCustomerId()),
                row.getMaxApplications(),
                row.getMaxTotalDiscount() == null ? null : Money.of(row.getMaxTotalDiscount(), currency),
                row.getRedeemedAt(),
                row.getEndsAt(),
                row.getActor(),
                new ReplayIdentity(row.getIdempotencyKey(), row.getRequestHash()),
                currency,
                RedemptionStatus.valueOf(row.getStatus()),
                row.getApplicationsUsed(),
                Money.of(row.getDiscountTotal(), currency),
                row.getEndReason() == null ? null : EndReason.valueOf(row.getEndReason()),
                row.getEndedAt());
    }
}
