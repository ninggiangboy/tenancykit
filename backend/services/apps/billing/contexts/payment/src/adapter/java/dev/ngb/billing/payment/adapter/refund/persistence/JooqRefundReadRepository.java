package dev.ngb.billing.payment.adapter.refund.persistence;

import static dev.ngb.billing_jooq.Tables.PAYMENTS;
import static dev.ngb.billing_jooq.Tables.REFUNDS;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import dev.ngb.billing.payment.application.refund.query.RefundListing;
import dev.ngb.billing.payment.application.refund.query.RefundReadRepository;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.billing.payment.domain.refund.RefundStatus;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing_jooq.tables.records.RefundsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects refunds straight into views. */
public class JooqRefundReadRepository implements RefundReadRepository {

    /** The refund fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(RefundListing.SCHEMA)
            .column("id", REFUNDS.ID)
            .column("payment_id", REFUNDS.PAYMENT_ID)
            .related("payment.customer_id", PAYMENTS.CUSTOMER_ID,
                    condition -> exists(selectOne().from(PAYMENTS).where(PAYMENTS.ID.eq(REFUNDS.PAYMENT_ID)).and(condition)))
            .column("status", REFUNDS.STATUS)
            .column("amount", REFUNDS.AMOUNT)
            .column("currency", REFUNDS.CURRENCY)
            .column("reason", REFUNDS.REASON)
            .column("created_at", REFUNDS.CREATED_AT)
            .column("completed_at", REFUNDS.COMPLETED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the refund listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqRefundReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<RefundView> byPayment(UUID paymentId) {
        return dsl.selectFrom(REFUNDS)
                .where(REFUNDS.PAYMENT_ID.eq(paymentId))
                .orderBy(REFUNDS.ID)
                .fetch(JooqRefundReadRepository::toView);
    }

    @Override
    public Optional<RefundView> findById(UUID id) {
        return dsl.selectFrom(REFUNDS)
                .where(REFUNDS.ID.eq(id))
                .fetchOptional(JooqRefundReadRepository::toView);
    }

    @Override
    public Page<RefundView> list(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : exists(selectOne()
                .from(PAYMENTS)
                .where(PAYMENTS.ID.eq(REFUNDS.PAYMENT_ID))
                .and(PAYMENTS.CUSTOMER_ID.in(customerIds)));
        return JooqLists.fetch(dsl, REFUNDS, scope, BINDING, query, JooqRefundReadRepository::toView);
    }

    private static RefundView toView(RefundsRecord row) {
        return new RefundView(
                row.getId(),
                row.getPaymentId(),
                RefundStatus.valueOf(row.getStatus()),
                Money.of(row.getAmount(), Currency.of(row.getCurrency().strip())),
                row.getReason(),
                row.getFailureReason(),
                row.getLedgerEntryId(),
                row.getCreatedAt(),
                row.getCompletedAt());
    }
}
