package dev.ngb.billing.payment.application.stripe.service;

import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.stripe.port.StripeEventRepository;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.payment.domain.stripe.StripeEvent;
import dev.ngb.billing.payment.domain.stripe.StripeEventId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import java.util.Objects;
import java.util.Optional;

/**
 * The one way a verified event is stored before it is acted on: the id and the received time are supplied here, so
 * a caller says which event arrived. Storing the same Stripe event twice keeps the first row. It must run inside
 * the caller's transaction, like every other write of the payment module.
 */
public class StripeEventStore {

    private final StripeEventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public StripeEventStore(StripeEventRepository events, IdGenerator ids, Clock clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return the stored event's id, or empty when the same Stripe event was already received
     */
    public Optional<StripeEventId> receive(StripeConfiguration configuration, PaymentProvider.ProviderEvent event, String payload) {
        StripeEventId id = new StripeEventId(ids.newId());
        boolean fresh = events.insertIfAbsent(StripeEvent.received(id, configuration.tenantId(), configuration.id(),
                configuration.accountId(), event.id(), event.type(), event.livemode(), payload, clock.now()));
        return fresh ? Optional.of(id) : Optional.empty();
    }
}
