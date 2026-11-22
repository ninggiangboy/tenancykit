package dev.ngb.billing.invoicing.application.subscription.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection of subscriptions and their history. */
public interface SubscriptionReadRepository {

    /** One page of subscriptions; {@code customerIds} restricts the listing, and null reads the whole tenant. */
    Page<SubscriptionView> list(ListQuery query, List<UUID> customerIds);

    Optional<SubscriptionView> findById(UUID id);

    List<SubscriptionHistoryEntryView> history(UUID subscriptionId);
}
