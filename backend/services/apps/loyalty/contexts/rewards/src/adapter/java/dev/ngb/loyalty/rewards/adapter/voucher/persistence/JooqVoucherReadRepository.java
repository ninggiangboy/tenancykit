package dev.ngb.loyalty.rewards.adapter.voucher.persistence;

import static dev.ngb.loyalty_jooq.Tables.VOUCHERS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherListing;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherReadRepository;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherView;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import dev.ngb.loyalty_jooq.tables.records.VouchersRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * Selects vouchers into the view. A voucher's expiry is the stored instant and nothing else: a row that has outlived it
 * is still {@code ISSUED} here, so no job has to move it along.
 */
public class JooqVoucherReadRepository implements VoucherReadRepository {

    /** The voucher fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(VoucherListing.SCHEMA)
            .column("id", VOUCHERS.ID)
            .column("code", VOUCHERS.CODE)
            .column("reward_id", VOUCHERS.REWARD_ID)
            .column("status", VOUCHERS.STATUS)
            .column("issued_at", VOUCHERS.ISSUED_AT)
            .column("expires_at", VOUCHERS.EXPIRES_AT)
            .column("used_at", VOUCHERS.USED_AT)
            .build();

    private final DSLContext dsl;

    public JooqVoucherReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<VoucherView> page(UUID memberId, VoucherStatus status, ListQuery query) {
        Condition scope = status == null
                ? VOUCHERS.MEMBER_ID.eq(memberId)
                : VOUCHERS.MEMBER_ID.eq(memberId).and(VOUCHERS.STATUS.eq(status.name()));
        return JooqLists.fetch(dsl, VOUCHERS, scope, BINDING, query, JooqVoucherReadRepository::toView);
    }

    @Override
    public Optional<VoucherView> findByCode(String code) {
        return dsl.selectFrom(VOUCHERS)
                .where(VOUCHERS.CODE.eq(code))
                .fetchOptional(JooqVoucherReadRepository::toView);
    }

    private static VoucherView toView(VouchersRecord row) {
        return new VoucherView(
                row.getId(),
                row.getCode(),
                row.getRewardId(),
                row.getMemberId(),
                row.getExternalRef(),
                VoucherStatus.valueOf(row.getStatus()),
                row.getIssuedAt(),
                row.getExpiresAt(),
                row.getUsedAt(),
                row.getUseRef(),
                row.getVoidReason() == null ? null : VoidReason.valueOf(row.getVoidReason()));
    }
}
