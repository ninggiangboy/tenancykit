package dev.ngb.billing.invoicing.application.invoice.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection of invoices, their lines and their payment history. */
public interface InvoiceReadRepository {

    /**
     * One page of invoices filtered and ordered by {@code query}. {@code customerIds} restricts the listing to those
     * customers (null for no restriction, an empty list for none), which is how a Tenant System sees only the
     * customers mapped to it.
     */
    Page<InvoiceView> list(ListQuery query, List<UUID> customerIds);

    Optional<InvoiceView> findById(UUID id);

    List<InvoiceItemView> items(UUID invoiceId);

    List<InvoicePaymentView> payments(UUID invoiceId);
}
