package dev.ngb.billing.coupon.adapter.coupon.persistence;

import static dev.ngb.billing_jooq.Tables.PROMOTION_CODES;

import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeListing;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeView;
import dev.ngb.billing_jooq.tables.records.PromotionCodesRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a promotion code straight into the view. */
public class JooqPromotionCodeReadRepository implements PromotionCodeReadRepository {

    /** The promotion-code fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(PromotionCodeListing.SCHEMA)
            .column("id", PROMOTION_CODES.ID)
            .column("coupon_id", PROMOTION_CODES.COUPON_ID)
            .column("code", PROMOTION_CODES.CODE)
            .column("max_redemptions", PROMOTION_CODES.MAX_REDEMPTIONS)
            .column("redeemed_count", PROMOTION_CODES.REDEEMED_COUNT)
            .column("redeem_by", PROMOTION_CODES.REDEEM_BY)
            .column("active", PROMOTION_CODES.ACTIVE)
            .column("created_at", PROMOTION_CODES.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the promotion-code listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqPromotionCodeReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PromotionCodeView> findById(UUID id) {
        return dsl.selectFrom(PROMOTION_CODES).where(PROMOTION_CODES.ID.eq(id))
                .fetchOptional(JooqPromotionCodeReadRepository::toView);
    }

    @Override
    public Page<PromotionCodeView> list(ListQuery query, UUID couponId) {
        return JooqLists.fetch(dsl, PROMOTION_CODES, PROMOTION_CODES.COUPON_ID.eq(couponId), BINDING, query,
                JooqPromotionCodeReadRepository::toView);
    }

    private static PromotionCodeView toView(PromotionCodesRecord row) {
        return new PromotionCodeView(row.getId(), row.getCode(), row.getCouponId(), row.getMaxRedemptions(), row.getRedeemedCount(),
                row.getRedeemBy(), row.getActive(), row.getCreatedAt());
    }
}
