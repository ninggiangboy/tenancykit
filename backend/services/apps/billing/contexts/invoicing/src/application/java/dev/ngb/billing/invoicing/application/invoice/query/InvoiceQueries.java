package dev.ngb.billing.invoicing.application.invoice.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.util.List;
import java.util.UUID;

/** Read access to the current tenant's invoices, through the read models. */
public class InvoiceQueries {

    public record InvoiceDetail(InvoiceView invoice, List<InvoiceItemView> items, List<InvoicePaymentView> payments) {}

    private final UnitOfWork unitOfWork;
    private final InvoiceReadRepository invoices;
    private final CustomerLookup customers;

    public InvoiceQueries(UnitOfWork unitOfWork, InvoiceReadRepository invoices, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.invoices = invoices;
        this.customers = customers;
    }

    /** A Tenant System sees only the invoices of the customers it mapped; any other caller sees the whole tenant. */
    public Page<InvoiceView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            List<UUID> visible = null;
            if (caller != null) {
                List<CustomerId> mapped = customers.mappedTo(caller);
                if (mapped.isEmpty()) {
                    return query.<InvoiceView>emptyPage();
                }
                visible = mapped.stream().map(CustomerId::value).toList();
            }
            return invoices.list(query, visible);
        });
    }

    /** Invoices of one customer, for the portal principal that is fixed to that customer. */
    public Page<InvoiceView> listForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> invoices.list(query, List.of(customerId.value())));
    }

    public InvoiceDetail get(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> {
            InvoiceView invoice = invoices.findById(id).orElseThrow(() -> new NotFoundException("invoice not found"));
            if (caller != null && customers.findFor(invoice.customerId(), caller).isEmpty()) {
                throw new NotFoundException("invoice not found");
            }
            return new InvoiceDetail(invoice, invoices.items(id), invoices.payments(id));
        });
    }
}
