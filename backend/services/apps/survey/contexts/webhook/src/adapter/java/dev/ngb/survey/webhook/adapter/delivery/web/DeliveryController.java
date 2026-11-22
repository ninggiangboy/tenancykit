package dev.ngb.survey.webhook.adapter.delivery.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.shared.SurveyPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.webhook.application.delivery.command.ManageDeliveryUseCase;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryAttemptView;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryListing;
import dev.ngb.survey.webhook.application.delivery.query.DeliveryQueries;
import dev.ngb.survey.webhook.application.delivery.query.WebhookDeliveryView;
import dev.ngb.survey.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.survey.webhook.domain.delivery.WebhookDeliveryId;
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
@RequestMapping("/api/v1")
public class DeliveryController {

    public record DeliveryResponse(
            String id, String endpointId, String eventId, String eventType, String status, int attempts, Instant nextAttemptAt,
            Integer lastStatusCode, String lastError, Instant createdAt, Instant completedAt) {
        static DeliveryResponse of(WebhookDelivery d) {
            return new DeliveryResponse(d.id().toString(), d.endpointId().toString(), d.eventId().toString(), d.eventType(), d.status().name(),
                    d.attempts(), d.nextAttemptAt(), d.lastStatusCode(), d.lastError(), d.createdAt(), d.completedAt());
        }

        static DeliveryResponse ofView(WebhookDeliveryView v) {
            return new DeliveryResponse(v.id().toString(), v.endpointId().toString(), v.eventId().toString(), v.eventType(), v.status().name(),
                    v.attempts(), v.nextAttemptAt(), v.lastStatusCode(), v.lastError(), v.createdAt(), v.completedAt());
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

    public DeliveryController(ManageDeliveryUseCase deliveries, DeliveryQueries deliveryQueries) {
        this.deliveries = deliveries;
        this.deliveryQueries = deliveryQueries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link DeliveryListing}. */
    @Operation(
            summary = "List webhook deliveries",
            description =
                    "Lists the attempts survey made to report an event to an endpoint, with the list-query contract's "
                    + "filtering, sorting and paging. A delivery that exhausted its attempts is `DEAD` and stays visible "
                    + "here so it can be resent. Requires `survey:webhook:manage`.")
    @GetMapping("/webhook-deliveries")
    @Listing(DeliveryListing.class)
    @RequiresPermission(SurveyPermissions.WEBHOOK_MANAGE)
    public Page<DeliveryResponse> listDeliveries(ListQuery query) {
        return deliveryQueries.list(query).map(DeliveryResponse::ofView);
    }

    @Operation(
            summary = "Get a webhook delivery",
            description =
                    "Returns one delivery with its state and every attempt: the status code, error, response excerpt and "
                    + "latency. An unknown id answers `404`. Requires `survey:webhook:manage`.")
    @GetMapping("/webhook-deliveries/{id}")
    @RequiresPermission(SurveyPermissions.WEBHOOK_MANAGE)
    public DeliveryDetailResponse getDelivery(@PathVariable String id) {
        DeliveryQueries.Detail detail = deliveryQueries.get(UUID.fromString(id));
        return new DeliveryDetailResponse(DeliveryResponse.ofView(detail.delivery()), detail.delivery().body(),
                detail.attempts().stream().map(AttemptResponse::ofView).toList());
    }

    /** Queues a fresh delivery of the same stored event to the same endpoint. Answers 202: sending happens asynchronously. */
    @Operation(
            summary = "Resend a webhook delivery",
            description =
                    "Queues a fresh delivery of the same stored event to the same endpoint and answers `202`: sending "
                    + "happens asynchronously. The original keeps its history. Requires `survey:webhook:manage`.")
    @PostMapping("/webhook-deliveries/{id}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(SurveyPermissions.WEBHOOK_MANAGE)
    public DeliveryResponse resend(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return DeliveryResponse.of(deliveries.resend(WebhookDeliveryId.of(id)));
    }
}
