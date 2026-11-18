package dev.ngb.billing.usage.application.usage.query;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only projections over usage events. No write side and no aggregate is involved. */
public interface UsageEventReadRepository {

    /** One page of usage events; {@code customerIds} restricts it, and null reads the whole tenant. */
    Page<UsageEventView> page(ListQuery query, List<UUID> customerIds);

    List<UsageRecordView> find(CustomerId customerId, Set<String> metrics, Instant from, Instant to, int limit);

    List<UsageTotalView> totals(CustomerId customerId, Set<String> metrics, Instant from, Instant to);
}
