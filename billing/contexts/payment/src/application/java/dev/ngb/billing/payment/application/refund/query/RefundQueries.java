package dev.ngb.billing.payment.application.refund.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.payment.application.payment.query.PaymentReadRepository;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.util.List;
import java.util.UUID;

/** Reads refunds for the GET handler. */
public class RefundQueries {

    private final UnitOfWork unitOfWork;
    private final RefundReadRepository refunds;
    private final PaymentReadRepository payments;
    private final CustomerLookup customers;
    private final CustomerVisibility visibility;

    public RefundQueries(
            UnitOfWork unitOfWork, RefundReadRepository refunds, PaymentReadRepository payments, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.refunds = refunds;
        this.payments = payments;
        this.customers = customers;
        this.visibility = new CustomerVisibility(customers);
    }

    public RefundView get(UUID id) {
        return get(id, null);
    }

    /** A Tenant System sees only refunds of the customers it mapped; any other caller sees the whole tenant. */
    public Page<RefundView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            List<UUID> visible = null;
            if (caller != null) {
                List<CustomerId> mapped = customers.mappedTo(caller);
                if (mapped.isEmpty()) {
                    return query.<RefundView>emptyPage();
                }
                visible = mapped.stream().map(CustomerId::value).toList();
            }
            return refunds.list(query, visible);
        });
    }

    /** Refunds of one customer's payments, for the portal principal that is fixed to that customer. */
    public Page<RefundView> listForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> refunds.list(query, List.of(customerId.value())));
    }

    public RefundView get(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> {
            RefundView refund = refunds.findById(id).orElseThrow(() -> new NotFoundException("refund not found"));
            if (caller != null) {
                var payment = payments.findById(refund.paymentId()).orElseThrow(() -> new NotFoundException("refund not found"));
                visibility.requireVisible(payment.customerId(), caller, () -> new NotFoundException("refund not found"));
            }
            return refund;
        });
    }
}
