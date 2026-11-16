package dev.ngb.loyalty.rewards.adapter.voucher.persistence;

import static dev.ngb.loyalty_jooq.Tables.VOUCHERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherId;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import dev.ngb.loyalty_jooq.tables.records.VouchersRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The vouchers this tenant issues.
 *
 * <p>The insert names the unique {@code (tenant_id, code)} pair and does nothing on a conflict, so a generated code that
 * this tenant already holds inserts nothing and the caller tries another. The two state changes match the status they
 * expect, so a conditional update that no longer applies changes no row.
 */
public class JooqVoucherRepository implements VoucherRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqVoucherRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean add(Voucher voucher) {
        return dsl.insertInto(VOUCHERS)
                        .set(VOUCHERS.TENANT_ID, tenantId().value())
                        .set(VOUCHERS.ID, voucher.id().value())
                        .set(VOUCHERS.CODE, voucher.code())
                        .set(VOUCHERS.REWARD_ID, voucher.rewardId())
                        .set(VOUCHERS.MEMBER_ID, voucher.memberId())
                        .set(VOUCHERS.REDEMPTION_ID, voucher.redemptionId())
                        .set(VOUCHERS.EXTERNAL_REF, voucher.externalRef())
                        .set(VOUCHERS.STATUS, voucher.status().name())
                        .set(VOUCHERS.ISSUED_AT, voucher.issuedAt())
                        .set(VOUCHERS.EXPIRES_AT, voucher.expiresAt())
                        .set(VOUCHERS.USED_AT, voucher.usedAt())
                        .set(VOUCHERS.USE_REF, voucher.useRef())
                        .set(VOUCHERS.VOID_REASON, voucher.voidReason() == null ? null : voucher.voidReason().name())
                        .onConflict(VOUCHERS.TENANT_ID, VOUCHERS.CODE)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<Voucher> lockByCode(String code) {
        return dsl.selectFrom(VOUCHERS).where(VOUCHERS.CODE.eq(code)).forUpdate().fetchOptional(JooqVoucherRepository::toDomain);
    }

    @Override
    public Optional<Voucher> lockByRedemption(UUID redemptionId) {
        return dsl.selectFrom(VOUCHERS)
                .where(VOUCHERS.REDEMPTION_ID.eq(redemptionId))
                .forUpdate()
                .fetchOptional(JooqVoucherRepository::toDomain);
    }

    @Override
    public Optional<Voucher> findByRedemption(UUID redemptionId) {
        return dsl.selectFrom(VOUCHERS)
                .where(VOUCHERS.REDEMPTION_ID.eq(redemptionId))
                .fetchOptional(JooqVoucherRepository::toDomain);
    }

    @Override
    public void markUsed(VoucherId id, String useRef, Instant usedAt) {
        int updated = dsl.update(VOUCHERS)
                .set(VOUCHERS.STATUS, VoucherStatus.USED.name())
                .set(VOUCHERS.USED_AT, usedAt)
                .set(VOUCHERS.USE_REF, useRef)
                .where(VOUCHERS.ID.eq(id.value()))
                .and(VOUCHERS.TENANT_ID.eq(tenantId().value()))
                .and(VOUCHERS.STATUS.eq(VoucherStatus.ISSUED.name()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("voucher " + id + " was not marked used");
        }
    }

    @Override
    public void voidIssued(VoucherId id, VoidReason reason) {
        int updated = dsl.update(VOUCHERS)
                .set(VOUCHERS.STATUS, VoucherStatus.VOIDED.name())
                .set(VOUCHERS.VOID_REASON, reason.name())
                .where(VOUCHERS.ID.eq(id.value()))
                .and(VOUCHERS.TENANT_ID.eq(tenantId().value()))
                .and(VOUCHERS.STATUS.eq(VoucherStatus.ISSUED.name()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("voucher " + id + " was not voided");
        }
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    private static Voucher toDomain(VouchersRecord row) {
        return Voucher.restore(
                new TenantId(row.getTenantId()),
                new VoucherId(row.getId()),
                row.getCode(),
                row.getRewardId(),
                row.getMemberId(),
                row.getRedemptionId(),
                row.getExternalRef(),
                VoucherStatus.valueOf(row.getStatus()),
                row.getIssuedAt(),
                row.getExpiresAt(),
                row.getUsedAt(),
                row.getUseRef(),
                row.getVoidReason() == null ? null : VoidReason.valueOf(row.getVoidReason()));
    }
}
