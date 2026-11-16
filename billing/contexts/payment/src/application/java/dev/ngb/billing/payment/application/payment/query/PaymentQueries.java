package dev.ngb.billing.payment.application.payment.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.payment.application.refund.query.RefundReadRepository;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.util.List;
import java.util.UUID;

/** Read access to the current tenant's payments and refunds, through the read models. */
public class PaymentQueries {

    public record PaymentDetail(PaymentView payment, List<RefundView> refunds, List<PaymentAttemptView> attempts) {}

    private final UnitOfWork unitOfWork;
    private final PaymentReadRepository payments;
    private final RefundReadRepository refunds;
    private final CustomerLookup customers;
    private final CustomerVisibility visibility;

    public PaymentQueries(UnitOfWork unitOfWork, PaymentReadRepository payments, RefundReadRepository refunds, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.payments = payments;
        this.refunds = refunds;
        this.customers = customers;
        this.visibility = new CustomerVisibility(customers);
    }

    /** A Tenant System sees only payments of the customers it mapped; any other caller sees the whole tenant. */
    public Page<PaymentView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            List<UUID> visible = null;
            if (caller != null) {
                List<CustomerId> mapped = customers.mappedTo(caller);
                if (mapped.isEmpty()) {
                    return query.<PaymentView>emptyPage();
                }
                visible = mapped.stream().map(CustomerId::value).toList();
            }
            return payments.list(query, visible);
        });
    }

    public PaymentDetail payment(UUID id) {
        return payment(id, null);
    }

    /** Payments of one customer, for the portal principal that is fixed to that customer. */
    public Page<PaymentView> listForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> payments.list(query, List.of(customerId.value())));
    }

    /** The payment only when it belongs to {@code customerId}. */
    public PaymentDetail paymentForCustomer(UUID id, CustomerId customerId) {
        return unitOfWork.transactional(() -> payments.findById(id)
                .filter(payment -> payment.customerId().equals(customerId))
                .map(payment -> new PaymentDetail(payment, refunds.byPayment(id), payments.attempts(id)))
                .orElseThrow(() -> new NotFoundException("payment not found")));
    }

    public PaymentDetail payment(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> payments.findById(id)
                .map(payment -> {
                    visibility.requireVisible(payment.customerId(), caller, () -> new NotFoundException("payment not found"));
                    return new PaymentDetail(payment, refunds.byPayment(id), payments.attempts(id));
                })
                .orElseThrow(() -> new NotFoundException("payment not found")));
    }
}
