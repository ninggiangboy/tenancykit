package dev.ngb.crm.webhook.adapter.delivery.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.crm.shared.CrmPermissions;
import dev.ngb.crm.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.crm.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.crm.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.crm.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.crm.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.crm.webhook.domain.delivery.WebhookDeliveryId;
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
            Instant nextAttemptAt, Integer lastStatusCode, String lastError, Instant createdAt, Instant completedAt) {

        static DeliveryResponse of(WebhookDelivery d) {
            return new DeliveryResponse(
                    d.id().toString(), d.endpointId().toString(), d.eventId().toString(), d.eventType(),
                    d.status().name(), d.attempts(), d.nextAttemptAt(), d.lastStatusCode(), d.lastError(),
                    d.createdAt(), d.completedAt());
        }

        static DeliveryResponse ofView(WebhookDeliveryView v) {
            return new DeliveryResponse(
                    v.id().toString(), v.endpointId().toString(), v.eventId().toString(), v.eventType(),
                    v.status().name(), v.attempts(), v.nextAttemptAt(), v.lastStatusCode(), v.lastError(),
                    v.createdAt(), v.completedAt());
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
    @RequiresPermission(CrmPermissions.WEBHOOK_MANAGE)
    public Page<DeliveryResponse> listDeliveries(ListQuery query) {
        return deliveryQueries.list(query).map(DeliveryResponse::ofView);
    }

    /** Queues a fresh delivery of the same stored event to the same endpoint. Answers 202: sending happens asynchronously. */
    @PostMapping("/webhook-deliveries/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(CrmPermissions.WEBHOOK_MANAGE)
    public DeliveryResponse retry(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return DeliveryResponse.of(deliveries.resend(WebhookDeliveryId.of(id)));
    }
}
