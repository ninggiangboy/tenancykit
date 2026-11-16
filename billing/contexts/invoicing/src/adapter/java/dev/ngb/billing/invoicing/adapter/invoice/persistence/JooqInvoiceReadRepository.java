package dev.ngb.billing.invoicing.adapter.invoice.persistence;

import static dev.ngb.billing_jooq.Tables.INVOICES;
import static dev.ngb.billing_jooq.Tables.INVOICE_ITEMS;
import static dev.ngb.billing_jooq.Tables.INVOICE_PAYMENTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.billing.invoicing.application.invoice.query.InvoiceItemView;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceListing;
import dev.ngb.billing.invoicing.application.invoice.query.InvoicePaymentView;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceReadRepository;
import dev.ngb.billing.invoicing.application.invoice.query.InvoiceView;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceOrigin;
import dev.ngb.billing.invoicing.domain.invoice.InvoicePayment;
import dev.ngb.billing.invoicing.domain.invoice.InvoiceStatus;
import dev.ngb.billing.invoicing.domain.invoice.ItemSource;
import dev.ngb.billing.invoicing.domain.invoice.Settlement;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.InvoicesRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects invoices, lines and payment history straight into views; no aggregate is built. */
public class JooqInvoiceReadRepository implements InvoiceReadRepository {

    /** The invoice fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(InvoiceListing.SCHEMA)
            .column("id", INVOICES.ID)
            .column("customer_id", INVOICES.CUSTOMER_ID)
            .column("wallet_id", INVOICES.WALLET_ID)
            .column("currency", INVOICES.CURRENCY)
            .column("number", INVOICES.NUMBER)
            .column("origin", INVOICES.ORIGIN)
            .column("status", INVOICES.STATUS)
            .column("total", INVOICES.TOTAL)
            .column("period_start", INVOICES.PERIOD_START)
            .column("period_end", INVOICES.PERIOD_END)
            .column("settlement", INVOICES.SETTLEMENT)
            .column("created_at", INVOICES.CREATED_AT)
            .column("issued_at", INVOICES.ISSUED_AT)
            .column("paid_at", INVOICES.PAID_AT)
            .column("voided_at", INVOICES.VOIDED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the invoice listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqInvoiceReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<InvoiceView> list(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : INVOICES.CUSTOMER_ID.in(customerIds);
        return JooqLists.fetch(dsl, INVOICES, scope, BINDING, query, JooqInvoiceReadRepository::toView);
    }

    @Override
    public Optional<InvoiceView> findById(UUID id) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.ID.eq(id))
                .fetchOptional(JooqInvoiceReadRepository::toView);
    }

    @Override
    public List<InvoiceItemView> items(UUID invoiceId) {
        return dsl.select(INVOICE_ITEMS.ID, INVOICE_ITEMS.SOURCE, INVOICE_ITEMS.CHARGE_ID, INVOICE_ITEMS.SUBSCRIPTION_ID, INVOICE_ITEMS.PERIOD_START,
                        INVOICE_ITEMS.PERIOD_END, INVOICE_ITEMS.DESCRIPTION, INVOICE_ITEMS.CURRENCY, INVOICE_ITEMS.AMOUNT, INVOICE_ITEMS.INCURRED_AT)
                .from(INVOICE_ITEMS)
                .where(INVOICE_ITEMS.INVOICE_ID.eq(invoiceId))
                .orderBy(INVOICE_ITEMS.INCURRED_AT, INVOICE_ITEMS.ID)
                .fetch(row -> new InvoiceItemView(
                        row.value1(),
                        ItemSource.valueOf(row.value2()),
                        row.value3(),
                        row.value4(),
                        row.value5(),
                        row.value6(),
                        row.value7(),
                        Money.of(row.value9(), Currency.of(row.value8().strip())),
                        row.value10()));
    }

    @Override
    public List<InvoicePaymentView> payments(UUID invoiceId) {
        return dsl.select(INVOICE_PAYMENTS.PAYMENT_ID, INVOICE_PAYMENTS.CURRENCY, INVOICE_PAYMENTS.AMOUNT, INVOICE_PAYMENTS.STATUS,
                        INVOICE_PAYMENTS.FAILURE_REASON, INVOICE_PAYMENTS.LEDGER_ENTRY_ID, INVOICE_PAYMENTS.STARTED_AT, INVOICE_PAYMENTS.SETTLED_AT)
                .from(INVOICE_PAYMENTS)
                .where(INVOICE_PAYMENTS.INVOICE_ID.eq(invoiceId))
                .orderBy(INVOICE_PAYMENTS.STARTED_AT, INVOICE_PAYMENTS.PAYMENT_ID)
                .fetch(row -> new InvoicePaymentView(
                        row.value1(),
                        Money.of(row.value3(), Currency.of(row.value2().strip())),
                        InvoicePayment.Status.valueOf(row.value4()),
                        row.value5(),
                        row.value6(),
                        row.value7(),
                        row.value8()));
    }

    private static InvoiceView toView(InvoicesRecord row) {
        return new InvoiceView(
                row.getId(),
                row.getNumber(),
                new CustomerId(row.getCustomerId()),
                new WalletId(row.getWalletId()),
                InvoiceStatus.valueOf(row.getStatus()),
                InvoiceOrigin.valueOf(row.getOrigin()),
                Money.of(row.getTotal(), Currency.of(row.getCurrency().strip())),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                row.getSettlement() == null ? null : Settlement.valueOf(row.getSettlement()),
                row.getVoidReason(),
                row.getCreatedAt(),
                row.getIssuedAt(),
                row.getPaidAt(),
                row.getVoidedAt());
    }
}
