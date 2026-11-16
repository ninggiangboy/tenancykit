package dev.ngb.billing.money.adapter.charge.persistence;

import static dev.ngb.billing_jooq.Tables.CHARGES;
import static dev.ngb.billing_jooq.Tables.CHARGE_DISCOUNTS;
import static dev.ngb.billing_jooq.Tables.WALLETS;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.application.charge.query.ChargeListing;
import dev.ngb.billing.money.application.charge.query.ChargeReadRepository;
import dev.ngb.billing.money.application.charge.query.ChargeView;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.ChargesRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects a charge straight into the view, with its discount lines; the aggregate is never built. */
public class JooqChargeReadRepository implements ChargeReadRepository {

    /** The charge fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(ChargeListing.SCHEMA)
            .column("id", CHARGES.ID)
            .column("wallet_id", CHARGES.WALLET_ID)
            .related("wallet.customer_id", WALLETS.CUSTOMER_ID,
                    condition -> exists(selectOne().from(WALLETS).where(WALLETS.ID.eq(CHARGES.WALLET_ID)).and(condition)))
            .column("source", CHARGES.SOURCE)
            .column("status", CHARGES.STATUS)
            .column("amount", CHARGES.AMOUNT)
            .column("currency", CHARGES.CURRENCY)
            .column("reference", CHARGES.REFERENCE)
            .column("usage_ref", CHARGES.USAGE_REF)
            .column("reservation_id", CHARGES.RESERVATION_ID)
            .column("created_at", CHARGES.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the charge listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqChargeReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ChargeView> findById(ChargeId id) {
        return dsl.selectFrom(CHARGES)
                .where(CHARGES.ID.eq(id.value()))
                .fetchOptional(row -> toView(row, discountsOf(row.getId())));
    }

    @Override
    public Page<ChargeView> list(ListQuery query, List<UUID> walletIds) {
        if (walletIds != null && walletIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = walletIds == null ? noCondition() : CHARGES.WALLET_ID.in(walletIds);
        return JooqLists.fetch(dsl, CHARGES, scope, BINDING, query, row -> toView(row, discountsOf(row.getId())));
    }

    private List<DiscountLine> discountsOf(UUID chargeId) {
        return dsl.selectFrom(CHARGE_DISCOUNTS)
                .where(CHARGE_DISCOUNTS.CHARGE_ID.eq(chargeId))
                .orderBy(CHARGE_DISCOUNTS.SEQ)
                .fetch(row -> new DiscountLine(row.getRedemptionId(), row.getCouponId(), row.getCouponName(), row.getBenefitType(),
                        Money.of(row.getAmount(), Currency.of(row.getCurrency().strip()))));
    }

    private static ChargeView toView(ChargesRecord row, List<DiscountLine> discounts) {
        Currency currency = Currency.of(row.getCurrency().strip());
        Money net = Money.of(row.getAmount(), currency);
        Money gross = net.plus(Money.of(row.getDiscountTotal(), currency));
        return new ChargeView(
                new ChargeId(row.getId()),
                new WalletId(row.getWalletId()),
                ChargeStatus.valueOf(row.getStatus()),
                row.getFailureReason(),
                net,
                gross,
                discounts,
                row.getReservationId() == null ? null : new ReservationId(row.getReservationId()),
                row.getUsageRef(),
                row.getReference(),
                ChargeSource.valueOf(row.getSource()),
                row.getCreatedAt());
    }
}
