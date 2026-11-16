package dev.ngb.billing.payment.adapter.payment.persistence;

import static dev.ngb.billing_jooq.Tables.PAYMENTS;
import static dev.ngb.billing_jooq.Tables.PAYMENT_ATTEMPTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.billing.payment.application.payment.query.PaymentAttemptView;
import dev.ngb.billing.payment.application.payment.query.PaymentReadRepository;
import dev.ngb.billing.payment.application.payment.query.PaymentListing;
import dev.ngb.billing.payment.application.payment.query.PaymentView;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.PaymentsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects payments and their failed attempts straight into views. */
public class JooqPaymentReadRepository implements PaymentReadRepository {

    /** The payment fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(PaymentListing.SCHEMA)
            .column("id", PAYMENTS.ID)
            .column("wallet_id", PAYMENTS.WALLET_ID)
            .column("customer_id", PAYMENTS.CUSTOMER_ID)
            .column("status", PAYMENTS.STATUS)
            .column("currency", PAYMENTS.CURRENCY)
            .column("amount", PAYMENTS.AMOUNT)
            .column("refunded_amount", PAYMENTS.REFUNDED_AMOUNT)
            .column("invoice_id", PAYMENTS.INVOICE_ID)
            .column("ledger_entry_id", PAYMENTS.LEDGER_ENTRY_ID)
            .column("created_at", PAYMENTS.CREATED_AT)
            .column("updated_at", PAYMENTS.UPDATED_AT)
            .column("completed_at", PAYMENTS.COMPLETED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the payment listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqPaymentReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<PaymentView> list(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : PAYMENTS.CUSTOMER_ID.in(customerIds);
        return JooqLists.fetch(dsl, PAYMENTS, scope, BINDING, query, JooqPaymentReadRepository::toView);
    }

    @Override
    public Optional<PaymentView> findById(UUID id) {
        return dsl.selectFrom(PAYMENTS)
                .where(PAYMENTS.ID.eq(id))
                .fetchOptional(JooqPaymentReadRepository::toView);
    }

    @Override
    public List<PaymentAttemptView> attempts(UUID paymentId) {
        return dsl.select(PAYMENT_ATTEMPTS.FAILURE_CODE, PAYMENT_ATTEMPTS.FAILURE_MESSAGE, PAYMENT_ATTEMPTS.OCCURRED_AT)
                .from(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.PAYMENT_ID.eq(paymentId))
                .orderBy(PAYMENT_ATTEMPTS.OCCURRED_AT, PAYMENT_ATTEMPTS.ID)
                .fetch(row -> new PaymentAttemptView(row.value1(), row.value2(), row.value3()));
    }

    private static PaymentView toView(PaymentsRecord row) {
        Currency money = Currency.of(row.getCurrency().strip());
        return new PaymentView(
                row.getId(),
                new WalletId(row.getWalletId()),
                new CustomerId(row.getCustomerId()),
                PaymentStatus.valueOf(row.getStatus()),
                Money.of(row.getAmount(), money),
                Money.of(row.getRefundedAmount(), money),
                row.getCheckoutSessionId(),
                row.getPaymentIntentId(),
                row.getCheckoutUrl(),
                row.getFailureReason(),
                row.getLedgerEntryId(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getCompletedAt());
    }
}
