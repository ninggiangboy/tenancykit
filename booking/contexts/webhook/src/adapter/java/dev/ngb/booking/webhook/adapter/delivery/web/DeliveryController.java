package dev.ngb.booking.webhook.adapter.delivery.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.booking.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.booking.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.booking.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.booking.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.booking.webhook.domain.delivery.WebhookDeliveryId;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery history and a retry of a finished one.
 *
 * <p>A delivery that exhausted its attempts is {@code DEAD} and stays visible here, so a Tenant can retry it once its
 * receiver is healthy again.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class DeliveryController {

    public record DeliveryResponse(
            String id, String endpointId, String eventId, String eventType, String status, int attempts,
            Integer lastStatusCode, String lastError, Instant nextAttemptAt) {

        static DeliveryResponse of(WebhookDelivery d) {
            return new DeliveryResponse(
                    d.id().toString(), d.endpointId().toString(), d.eventId().toString(), d.eventType(),
                    status(d.status().name()), d.attempts(), d.lastStatusCode(), d.lastError(),
                    d.nextAttemptAt());
        }

        static DeliveryResponse ofView(WebhookDeliveryView v) {
            return new DeliveryResponse(
                    v.id().toString(), v.endpointId().toString(), v.eventId().toString(), v.eventType(),
                    status(v.status().name()), v.attempts(), v.lastStatusCode(), v.lastError(),
                    v.nextAttemptAt());
        }

        /** The wire name of a stored status: a claimed row is retrying, and a stored success is delivered. */
        private static String status(String stored) {
            return switch (stored) {
                case "SUCCEEDED" -> "DELIVERED";
                case "IN_PROGRESS" -> "RETRYING";
                default -> stored;
            };
        }
    }

    private final ManageDeliveryUseCase deliveries;
    private final DeliveryQueries deliveryQueries;

    public DeliveryController(ManageDeliveryUseCase deliveries, DeliveryQueries deliveryQueries) {
        this.deliveries = deliveries;
        this.deliveryQueries = deliveryQueries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link DeliveryListing}. */
    @GetMapping("/webhook-deliveries")
    @Listing(DeliveryListing.class)
    @RequiresPermission(BookingPermissions.WEBHOOK_READ)
    public Page<DeliveryResponse> listDeliveries(ListQuery query) {
        return deliveryQueries.list(query).map(DeliveryResponse::ofView);
    }

    /** Queues a fresh delivery of the same stored event to the same endpoint. Answers 202: sending happens asynchronously. */
    @PostMapping("/webhook-deliveries/{deliveryId}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(BookingPermissions.WEBHOOK_MANAGE)
    public DeliveryResponse retry(
            @PathVariable String deliveryId,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return DeliveryResponse.of(deliveries.resend(WebhookDeliveryId.of(deliveryId)));
    }
}
