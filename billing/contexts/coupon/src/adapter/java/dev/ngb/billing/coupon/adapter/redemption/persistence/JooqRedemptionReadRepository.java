package dev.ngb.billing.coupon.adapter.redemption.persistence;

import static dev.ngb.billing_jooq.Tables.COUPON_REDEMPTIONS;
import static dev.ngb.billing_jooq.Tables.COUPONS;

import dev.ngb.billing.coupon.application.redemption.query.RedemptionListing;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionView;
import dev.ngb.billing_jooq.tables.records.CouponRedemptionsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a redemption straight into the view, reading the coupon name once per page. */
public class JooqRedemptionReadRepository implements RedemptionReadRepository {

    /** The redemption fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(RedemptionListing.SCHEMA)
            .column("id", COUPON_REDEMPTIONS.ID)
            .column("coupon_id", COUPON_REDEMPTIONS.COUPON_ID)
            .column("code_id", COUPON_REDEMPTIONS.CODE_ID)
            .column("wallet_id", COUPON_REDEMPTIONS.WALLET_ID)
            .column("customer_id", COUPON_REDEMPTIONS.CUSTOMER_ID)
            .column("status", COUPON_REDEMPTIONS.STATUS)
            .column("max_applications", COUPON_REDEMPTIONS.MAX_APPLICATIONS)
            .column("applications_used", COUPON_REDEMPTIONS.APPLICATIONS_USED)
            .column("max_total_discount", COUPON_REDEMPTIONS.MAX_TOTAL_DISCOUNT)
            .column("discount_total", COUPON_REDEMPTIONS.DISCOUNT_TOTAL)
            .column("currency", COUPON_REDEMPTIONS.CURRENCY)
            .column("redeemed_at", COUPON_REDEMPTIONS.REDEEMED_AT)
            .column("ends_at", COUPON_REDEMPTIONS.ENDS_AT)
            .column("end_reason", COUPON_REDEMPTIONS.END_REASON)
            .column("ended_at", COUPON_REDEMPTIONS.ENDED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the redemption listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqRedemptionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<RedemptionView> findById(UUID id) {
        return dsl.select(COUPON_REDEMPTIONS.asterisk(), COUPONS.NAME)
                .from(COUPON_REDEMPTIONS)
                .join(COUPONS).on(COUPONS.ID.eq(COUPON_REDEMPTIONS.COUPON_ID))
                .where(COUPON_REDEMPTIONS.ID.eq(id))
                .fetchOptional(record -> toView(record.into(COUPON_REDEMPTIONS), record.get(COUPONS.NAME)));
    }

    @Override
    public Page<RedemptionView> list(ListQuery query, UUID walletId) {
        return JooqLists.fetchPage(dsl, COUPON_REDEMPTIONS, COUPON_REDEMPTIONS.WALLET_ID.eq(walletId), BINDING, query, rows -> {
            Map<UUID, String> names = dsl.select(COUPONS.ID, COUPONS.NAME)
                    .from(COUPONS)
                    .where(COUPONS.ID.in(rows.stream().map(CouponRedemptionsRecord::getCouponId).distinct().toList()))
                    .fetchMap(COUPONS.ID, COUPONS.NAME);
            return rows.stream().map(row -> toView(row, names.get(row.getCouponId()))).toList();
        });
    }

    private static RedemptionView toView(CouponRedemptionsRecord row, String couponName) {
        return new RedemptionView(
                row.getId(),
                row.getCouponId(),
                couponName,
                row.getCodeId(),
                row.getWalletId(),
                row.getCustomerId(),
                row.getStatus(),
                row.getMaxApplications(),
                row.getApplicationsUsed(),
                row.getMaxTotalDiscount(),
                row.getDiscountTotal(),
                row.getCurrency().strip(),
                row.getRedeemedAt(),
                row.getEndsAt(),
                row.getEndReason(),
                row.getEndedAt());
    }
}
