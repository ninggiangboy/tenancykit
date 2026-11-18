package dev.ngb.billing.payment.application.refund.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of refunds. */
public interface RefundReadRepository {

    List<RefundView> byPayment(UUID paymentId);

    Optional<RefundView> findById(UUID id);

    /** One page of refunds; {@code customerIds} restricts it to the customers of their payments, and null reads the tenant. */
    Page<RefundView> list(ListQuery query, List<UUID> customerIds);
}
