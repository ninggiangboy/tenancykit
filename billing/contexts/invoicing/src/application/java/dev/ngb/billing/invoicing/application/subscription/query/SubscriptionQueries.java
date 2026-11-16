package dev.ngb.billing.invoicing.application.subscription.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.util.List;
import java.util.UUID;

/** Read access to the current tenant's subscriptions, through the read models. */
public class SubscriptionQueries {

    public record SubscriptionDetail(SubscriptionView subscription, List<SubscriptionHistoryEntryView> history) {}

    private final UnitOfWork unitOfWork;
    private final SubscriptionReadRepository subscriptions;
    private final CustomerLookup customers;

    public SubscriptionQueries(UnitOfWork unitOfWork, SubscriptionReadRepository subscriptions, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.subscriptions = subscriptions;
        this.customers = customers;
    }

    /** A Tenant System sees only the subscriptions of the customers it mapped; any other caller sees the whole tenant. */
    public Page<SubscriptionView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            List<UUID> visible = null;
            if (caller != null) {
                List<CustomerId> mapped = customers.mappedTo(caller);
                if (mapped.isEmpty()) {
                    return query.<SubscriptionView>emptyPage();
                }
                visible = mapped.stream().map(CustomerId::value).toList();
            }
            return subscriptions.list(query, visible);
        });
    }

    /** Subscriptions of one customer, for the portal principal that is fixed to that customer. */
    public Page<SubscriptionView> listForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> subscriptions.list(query, List.of(customerId.value())));
    }

    public SubscriptionDetail get(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> {
            SubscriptionView subscription = subscriptions.findById(id).orElseThrow(() -> new NotFoundException("subscription not found"));
            if (caller != null && customers.findFor(subscription.customerId(), caller).isEmpty()) {
                throw new NotFoundException("subscription not found");
            }
            return new SubscriptionDetail(subscription, subscriptions.history(id));
        });
    }
}
