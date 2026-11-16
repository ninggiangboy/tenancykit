package dev.ngb.billing.payment.application.payment.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of payments and their failed attempts. */
public interface PaymentReadRepository {

    /**
     * One page of payments filtered and ordered by {@code query}. {@code customerIds} restricts the listing to those
     * customers (null for no restriction, an empty list for none), which is how a Tenant System sees only the
     * customers mapped to it.
     */
    Page<PaymentView> list(ListQuery query, List<UUID> customerIds);

    Optional<PaymentView> findById(UUID id);

    List<PaymentAttemptView> attempts(UUID paymentId);
}
