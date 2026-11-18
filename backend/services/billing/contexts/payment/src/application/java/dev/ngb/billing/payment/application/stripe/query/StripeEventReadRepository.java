package dev.ngb.billing.payment.application.stripe.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of Stripe events and their attempt history. */
public interface StripeEventReadRepository {

    /** One page of events, newest received first unless the query says otherwise. */
    Page<StripeEventView> list(ListQuery query);

    Optional<StripeEventView> findById(UUID id);

    List<EventAttemptView> attempts(UUID eventId);
}
