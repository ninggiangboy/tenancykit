package dev.ngb.billing.usage.application.usage.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.usage.api.lookup.UsageRecord;
import dev.ngb.billing.usage.api.lookup.UsageTotal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reads usage of the current tenant: history and totals for the API, and the period reads behind the usage lookup. */
public class UsageQueries {

    private final UnitOfWork unitOfWork;
    private final UsageEventReadRepository events;
    private final CustomerLookup customers;

    public UsageQueries(UnitOfWork unitOfWork, UsageEventReadRepository events, CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.events = events;
        this.customers = customers;
    }

    /** A Tenant System sees only the usage of the customers it mapped; any other caller sees the whole tenant. */
    public Page<UsageEventView> history(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> {
            List<UUID> visible = null;
            if (caller != null) {
                List<CustomerId> mapped = customers.mappedTo(caller);
                if (mapped.isEmpty()) {
                    return query.<UsageEventView>emptyPage();
                }
                visible = mapped.stream().map(CustomerId::value).toList();
            }
            return events.page(query, visible);
        });
    }

    /** Usage events of one customer, for the portal principal that is fixed to that customer. */
    public Page<UsageEventView> historyForCustomer(CustomerId customerId, ListQuery query) {
        return unitOfWork.transactional(() -> events.page(query, List.of(customerId.value())));
    }

    public List<UsageRecord> find(CustomerId customerId, Set<String> metrics, Instant from, Instant to, int limit) {
        requireWindow(from, to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("a period needs both from and to");
        }
        return unitOfWork.transactional(() -> events.find(customerId, metrics, from, to, limit).stream().map(UsageRecordView::toRecord).toList());
    }

    public List<UsageTotal> totals(CustomerId customerId, Set<String> metrics, Instant from, Instant to, java.util.UUID caller) {
        requireWindow(from, to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("a period needs both from and to");
        }
        return unitOfWork.transactional(() -> {
            requireCustomer(customerId, caller);
            return events.totals(customerId, metrics, from, to).stream().map(UsageTotalView::toTotal).toList();
        });
    }

    private void requireCustomer(CustomerId customerId, java.util.UUID caller) {
        customers.findFor(customerId, caller).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private static void requireWindow(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }
}
