package dev.ngb.billing.money.adapter.charge.persistence;

import static dev.ngb.billing_jooq.Tables.CHARGES;
import static dev.ngb.billing_jooq.Tables.CHARGE_DISCOUNTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.util.Causes;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.application.charge.port.ChargeRepository;
import dev.ngb.billing.money.domain.charge.Charge;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing_jooq.Keys;
import dev.ngb.billing_jooq.tables.records.ChargesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqChargeRepository implements ChargeRepository {

    private static final String KEY_CONSTRAINT = Keys.CHARGES_TENANT_ID_IDEMPOTENCY_KEY_KEY.getName();

    private final DSLContext dsl;

    public JooqChargeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Charge c) {
        try {
            dsl.insertInto(CHARGES)
                    .set(CHARGES.ID, c.id().value())
                    .set(CHARGES.TENANT_ID, c.tenantId().value())
                    .set(CHARGES.WALLET_ID, c.walletId().value())
                    .set(CHARGES.CURRENCY, c.amount().currency().code())
                    .set(CHARGES.AMOUNT, c.amount().minorUnits())
                    .set(CHARGES.DISCOUNT_TOTAL, c.discountTotal().minorUnits())
                    .set(CHARGES.SOURCE, c.source().name())
                    .set(CHARGES.STATUS, c.status().name())
                    .set(CHARGES.FAILURE_REASON, c.failureReason())
                    .set(CHARGES.RESERVATION_ID, c.reservationId() == null ? null : c.reservationId().value())
                    .set(CHARGES.USAGE_REF, c.usageRef())
                    .set(CHARGES.REFERENCE, c.reference())
                    .set(CHARGES.IDEMPOTENCY_KEY, c.idempotencyKey())
                    .set(CHARGES.REQUEST_HASH, c.requestHash())
                    .set(CHARGES.CREATED_AT, c.createdAt())
                    .execute();
            int seq = 0;
            for (DiscountLine line : c.discounts()) {
                seq++;
                dsl.insertInto(CHARGE_DISCOUNTS)
                        .set(CHARGE_DISCOUNTS.CHARGE_ID, c.id().value())
                        .set(CHARGE_DISCOUNTS.TENANT_ID, c.tenantId().value())
                        .set(CHARGE_DISCOUNTS.SEQ, seq)
                        .set(CHARGE_DISCOUNTS.REDEMPTION_ID, line.redemptionId())
                        .set(CHARGE_DISCOUNTS.COUPON_ID, line.couponId())
                        .set(CHARGE_DISCOUNTS.COUPON_NAME, line.couponName())
                        .set(CHARGE_DISCOUNTS.BENEFIT_TYPE, line.benefitType())
                        .set(CHARGE_DISCOUNTS.CURRENCY, line.amount().currency().code())
                        .set(CHARGE_DISCOUNTS.AMOUNT, line.amount().minorUnits())
                        .execute();
            }
        } catch (RuntimeException e) {
            if (Causes.violates(e, KEY_CONSTRAINT)) {
                throw new IdempotencyConflictException("charge", c.idempotencyKey());
            }
            throw e;
        }
    }

    @Override
    public Optional<Charge> findById(ChargeId id) {
        return dsl.selectFrom(CHARGES)
                .where(CHARGES.ID.eq(id.value()))
                .fetchOptional(row -> toDomain(row, discountsOf(row.getId())));
    }

    @Override
    public Optional<Charge> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(CHARGES)
                .where(CHARGES.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(row -> toDomain(row, discountsOf(row.getId())));
    }

    private List<DiscountLine> discountsOf(UUID chargeId) {
        return dsl.selectFrom(CHARGE_DISCOUNTS)
                .where(CHARGE_DISCOUNTS.CHARGE_ID.eq(chargeId))
                .orderBy(CHARGE_DISCOUNTS.SEQ)
                .fetch(row -> new DiscountLine(row.getRedemptionId(), row.getCouponId(), row.getCouponName(), row.getBenefitType(),
                        Money.of(row.getAmount(), Currency.of(row.getCurrency().strip()))));
    }

    private static Charge toDomain(ChargesRecord row, List<DiscountLine> discounts) {
        Currency currency = Currency.of(row.getCurrency().strip());
        return Charge.restore(
                new ChargeId(row.getId()),
                new TenantId(row.getTenantId()),
                new WalletId(row.getWalletId()),
                Money.of(row.getAmount(), currency),
                Money.of(row.getDiscountTotal(), currency),
                discounts,
                ChargeSource.valueOf(row.getSource()),
                ChargeStatus.valueOf(row.getStatus()),
                row.getFailureReason(),
                row.getReservationId() == null ? null : new ReservationId(row.getReservationId()),
                row.getUsageRef(),
                row.getReference(),
                new ReplayIdentity(row.getIdempotencyKey(), row.getRequestHash()),
                row.getCreatedAt());
    }
}
