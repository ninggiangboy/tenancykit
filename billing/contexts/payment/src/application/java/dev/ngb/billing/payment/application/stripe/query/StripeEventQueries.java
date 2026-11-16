package dev.ngb.billing.payment.application.stripe.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.UUID;

/** Reads Stripe webhook event history for the GET handlers. */
public class StripeEventQueries {


    public record Detail(StripeEventView event, List<EventAttemptView> attempts) {}

    private final UnitOfWork unitOfWork;
    private final StripeEventReadRepository events;

    public StripeEventQueries(UnitOfWork unitOfWork, StripeEventReadRepository events) {
        this.unitOfWork = unitOfWork;
        this.events = events;
    }

    public Page<StripeEventView> list(ListQuery query) {
        return unitOfWork.transactional(() -> events.list(query));
    }

    public Detail get(UUID id) {
        return unitOfWork.transactional(() -> events.findById(id)
                .map(event -> new Detail(event, events.attempts(id)))
                .orElseThrow(() -> new NotFoundException("stripe event not found")));
    }
}
