package dev.ngb.loyalty.webhook.application.delivery.port;

import dev.ngb.loyalty.webhook.domain.delivery.DeliveryAttempt;
import dev.ngb.loyalty.webhook.domain.delivery.DeliveryStatus;
import dev.ngb.loyalty.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.loyalty.webhook.domain.delivery.WebhookDeliveryId;
import dev.ngb.loyalty.webhook.domain.endpoint.WebhookEndpointId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Deliveries and their attempt log within the current tenant. */
public interface WebhookDeliveryRepository {

    /** @return false when this endpoint already has an original delivery for the event */
    boolean addIfAbsent(WebhookDelivery delivery);

    void add(WebhookDelivery delivery);

    Optional<WebhookDelivery> findById(WebhookDeliveryId id);

    /**
     * Marks as dead every claimed delivery whose lease ran out after its last allowed attempt (its worker died
     * during that attempt), so it is not sent again.
     */
    int killExhausted(Instant now, int maxAttempts);

    /**
     * Takes up to {@code limit} due deliveries of active endpoints, in a way no other worker can also take
     * them: each is marked in progress with its lease as the next due time, and its attempt counter goes up.
     */
    List<ClaimedDelivery> claimDue(Instant now, int limit, Duration lease);

    /**
     * Appends the attempt to the log and, only if the delivery is still on the attempt the worker was given,
     * moves it to its new state. A worker that was too slow and lost its lease therefore cannot overwrite the
     * state written by the worker that took over.
     */
    void recordAttempt(WebhookDeliveryId id, int claimedAttempts, DeliveryAttempt attempt, DeliveryStatus status, Instant nextAttemptAt, Instant completedAt);

    /**
     * Gives up on the deliveries still owed to an endpoint that is about to disappear, so nothing stays claimed for a
     * destination that can no longer be sent to. The history keeps its state.
     */
    int abandonUnfinished(WebhookEndpointId endpointId, Instant now);

    /**
     * Deletes finished deliveries and their attempt log older than {@code before}, oldest first, at most {@code limit}
     * deliveries per call. Returns how many deliveries were purged.
     */
    int purgeFinished(Instant before, int limit);
}
