package dev.ngb.billing.webhook.adapter.delivery.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.billing.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.billing.webhook.application.delivery.query.DeliveryAttemptView;
import dev.ngb.billing.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.billing.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.billing.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.billing.webhook.domain.delivery.DeliveryStatus;
import dev.ngb.billing.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.billing.webhook.domain.delivery.WebhookDeliveryId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Webhook delivery history and resending. */
@Tag(
        name = "Webhook deliveries",
        description =
                "The history of attempts to deliver an event to an endpoint, and resending one that finished. A "
                + "delivery that exhausted its attempts is DEAD and stays visible so it can be resent.")
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class DeliveryController {

    public record DeliveryResponse(
            String id, String endpointId, String eventId, String eventType, String status, int attempts, Instant nextAttemptAt, String resendOf,
            Integer lastStatusCode, String lastError, Instant createdAt, Instant completedAt) {
        static DeliveryResponse of(WebhookDelivery d) {
            return new DeliveryResponse(d.id().toString(), d.endpointId().toString(), d.eventId().toString(), d.eventType(), d.status().name(),
                    d.attempts(), d.nextAttemptAt(), d.resendOf() == null ? null : d.resendOf().toString(), d.lastStatusCode(), d.lastError(),
                    d.createdAt(), d.completedAt());
        }

        static DeliveryResponse ofView(WebhookDeliveryView v) {
            return new DeliveryResponse(v.id().toString(), v.endpointId().toString(), v.eventId().toString(), v.eventType(), v.status().name(),
                    v.attempts(), v.nextAttemptAt(), v.resendOf() == null ? null : v.resendOf().toString(), v.lastStatusCode(), v.lastError(),
                    v.createdAt(), v.completedAt());
        }
    }

    public record AttemptResponse(int attemptNo, Integer statusCode, String error, String responseExcerpt, long latencyMs, Instant attemptedAt) {
        static AttemptResponse ofView(DeliveryAttemptView a) {
            return new AttemptResponse(a.attemptNo(), a.statusCode(), a.error(), a.responseExcerpt(), a.latencyMs(), a.attemptedAt());
        }
    }

    public record DeliveryDetailResponse(DeliveryResponse delivery, String body, List<AttemptResponse> attempts) {}

    private final ManageDeliveryUseCase deliveries;
    private final DeliveryQueries deliveryQueries;
    private final CurrentActor currentActor;

    public DeliveryController(ManageDeliveryUseCase deliveries, DeliveryQueries deliveryQueries, CurrentActor currentActor) {
        this.deliveries = deliveries;
        this.deliveryQueries = deliveryQueries;
        this.currentActor = currentActor;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link DeliveryListing}. */
    @Operation(
            summary = "List webhook deliveries",
            description =
                    "Lists the attempts Billing made to report an event to an endpoint, with the list-query contract's "
                    + "filtering, sorting and paging. A delivery that exhausted its attempts is `DEAD` and stays visible "
                    + "here so it can be resent.")
    @GetMapping("/webhook-deliveries")
    @Listing(DeliveryListing.class)
    @RequiresPermission(BillingPermissions.WEBHOOK_READ)
    public Page<DeliveryResponse> listDeliveries(ListQuery query) {
        return deliveryQueries.list(query, currentActor.caller()).map(DeliveryResponse::ofView);
    }

    @Operation(
            summary = "Get a webhook delivery",
            description =
                    "Returns one delivery with its state and every attempt: the status code, error, response excerpt and "
                    + "latency. An unknown id answers `404`.")
    @GetMapping("/webhook-deliveries/{id}")
    @RequiresPermission(BillingPermissions.WEBHOOK_READ)
    public DeliveryDetailResponse getDelivery(@PathVariable String id) {
        DeliveryQueries.Detail detail = deliveryQueries.get(UUID.fromString(id), currentActor.caller());
        return new DeliveryDetailResponse(DeliveryResponse.ofView(detail.delivery()), detail.delivery().body(),
                detail.attempts().stream().map(AttemptResponse::ofView).toList());
    }

    /** Queues a fresh delivery of the same stored event to the same endpoint. Answers 202: sending happens asynchronously. */
    @Operation(
            summary = "Resend a webhook delivery",
            description =
                    "Queues a fresh delivery of the same stored event to the same endpoint and answers `202`: sending "
                    + "happens asynchronously. The original keeps its history.")
    @PostMapping("/webhook-deliveries/{id}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(BillingPermissions.WEBHOOK_MANAGE)
    public DeliveryResponse resend(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return DeliveryResponse.of(deliveries.resend(Audit.of(currentActor.get().auditName(), requestId), WebhookDeliveryId.of(id), currentActor.caller()));
    }
}
