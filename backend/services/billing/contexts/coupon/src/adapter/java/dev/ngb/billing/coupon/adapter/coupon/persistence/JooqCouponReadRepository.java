package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.COUPONS;

import dev.ngb.billing.coupon.application.coupon.query.CouponListing;
import dev.ngb.billing.coupon.application.coupon.query.CouponReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.CouponView;
import dev.ngb.billing_jooq.tables.records.CouponsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a coupon straight into the view; the aggregate is never built. */
public class JooqCouponReadRepository implements CouponReadRepository {

    /** The coupon fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(CouponListing.SCHEMA)
            .column("id", COUPONS.ID)
            .column("name", COUPONS.NAME)
            .column("benefit_type", COUPONS.BENEFIT_TYPE)
            .column("currency", COUPONS.CURRENCY)
            .column("status", COUPONS.STATUS)
            .column("stackable", COUPONS.STACKABLE)
            .column("redeem_by", COUPONS.REDEEM_BY)
            .column("redemptions_count", COUPONS.REDEMPTIONS_COUNT)
            .column("budget", COUPONS.BUDGET)
            .column("budget_used", COUPONS.BUDGET_USED)
            .column("created_by", COUPONS.CREATED_BY)
            .column("created_at", COUPONS.CREATED_AT)
            .column("deactivated_at", COUPONS.DEACTIVATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the coupon listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCouponReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<CouponView> findById(UUID id) {
        return dsl.selectFrom(COUPONS).where(COUPONS.ID.eq(id)).fetchOptional(JooqCouponReadRepository::toView);
    }

    @Override
    public Page<CouponView> list(ListQuery query) {
        return JooqLists.fetch(dsl, COUPONS, org.jooq.impl.DSL.noCondition(), BINDING, query, JooqCouponReadRepository::toView);
    }

    private static CouponView toView(CouponsRecord row) {
        return new CouponView(
                row.getId(),
                row.getName(),
                row.getBenefitType(),
                row.getPercentBasisPoints(),
                row.getAmountOff(),
                row.getCreditAmount(),
                row.getCurrency().strip(),
                row.getRedeemBy(),
                row.getMaxRedemptions(),
                row.getMaxPerCustomer(),
                row.getRedemptionsCount(),
                row.getBudget(),
                row.getBudgetUsed(),
                row.getStackable(),
                row.getStatus(),
                row.getCreatedBy(),
                row.getCreatedAt(),
                row.getDeactivatedAt(),
                row.getScopePlanIds() == null ? List.of() : Arrays.asList(row.getScopePlanIds()));
    }
}
