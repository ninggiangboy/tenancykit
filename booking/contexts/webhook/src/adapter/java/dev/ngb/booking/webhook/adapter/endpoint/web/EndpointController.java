package dev.ngb.booking.webhook.adapter.endpoint.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.webhook.application.endpoint.command.CreateEndpointUseCase;
import dev.ngb.booking.webhook.application.endpoint.command.ManageEndpointUseCase;
import dev.ngb.booking.shared.PreconditionRequiredException;
import dev.ngb.booking.webhook.application.endpoint.query.EndpointListing;
import dev.ngb.booking.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.booking.webhook.application.endpoint.query.WebhookEndpointView;
import dev.ngb.booking.webhook.domain.endpoint.EndpointStatus;
import dev.ngb.booking.webhook.domain.endpoint.WebhookEndpoint;
import dev.ngb.booking.webhook.domain.endpoint.WebhookEndpointId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook endpoints: where a Tenant wants its events delivered and which of the four types.
 *
 * <p>The signing secret appears in exactly two responses, creating an endpoint and rotating its secret, and never
 * again. A change is conditional on {@code If-Match}, because a Tenant System and a Tenant User may both hold an
 * endpoint.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class EndpointController {

    public record CreateEndpointRequest(String url, String description, List<String> eventTypes) {}

    public record UpdateEndpointRequest(String url, String description, List<String> eventTypes, String status) {}

    public record EndpointResponse(
            String id, String url, List<String> eventTypes, String status, String keyId, long version) {

        static EndpointResponse of(WebhookEndpoint e) {
            return new EndpointResponse(
                    e.id().toString(), e.url(), List.copyOf(e.eventTypes()), e.status().name(), e.keyId(),
                    e.version());
        }

        static EndpointResponse ofView(WebhookEndpointView v) {
            return new EndpointResponse(
                    v.id().toString(), v.url(), v.eventTypes(), v.status().name(), v.keyId(), v.version());
        }
    }

    /** The one response that carries the plaintext secret. */
    public record EndpointWithSecretResponse(EndpointResponse endpoint, String secret) {}

    private final CreateEndpointUseCase create;
    private final ManageEndpointUseCase endpoints;
    private final EndpointQueries endpointQueries;

    public EndpointController(
            CreateEndpointUseCase create, ManageEndpointUseCase endpoints, EndpointQueries endpointQueries) {
        this.create = create;
        this.endpoints = endpoints;
        this.endpointQueries = endpointQueries;
    }

    @PostMapping("/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BookingPermissions.WEBHOOK_MANAGE)
    public EndpointWithSecretResponse createEndpoint(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CreateEndpointRequest request) {
        CreateEndpointUseCase.Result result = create.execute(
                new CreateEndpointUseCase.Command(request.url(), request.description(), request.eventTypes()));
        return new EndpointWithSecretResponse(EndpointResponse.of(result.endpoint()), result.signingSecret());
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link EndpointListing}. */
    @GetMapping("/webhook-endpoints")
    @Listing(EndpointListing.class)
    @RequiresPermission(BookingPermissions.WEBHOOK_READ)
    public Page<EndpointResponse> listEndpoints(ListQuery query) {
        return endpointQueries.list(query).map(EndpointResponse::ofView);
    }

    @GetMapping("/webhook-endpoints/{endpointId}")
    @RequiresPermission(BookingPermissions.WEBHOOK_READ)
    public ResponseEntity<EndpointResponse> getEndpoint(@PathVariable String endpointId) {
        WebhookEndpointView endpoint = endpointQueries.get(UUID.fromString(endpointId));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(endpoint.version())).body(EndpointResponse.ofView(endpoint));
    }

    /** Changes only the fields that are present. Setting {@code status} to {@code DISABLED} pauses delivery without losing queued events. */
    @PatchMapping("/webhook-endpoints/{endpointId}")
    @RequiresPermission(BookingPermissions.WEBHOOK_MANAGE)
    @ConditionalUpdate
    public ResponseEntity<EndpointResponse> updateEndpoint(
            @PathVariable String endpointId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody UpdateEndpointRequest request) {
        EndpointStatus status = request.status() == null ? null : EndpointStatus.valueOf(request.status());
        WebhookEndpoint endpoint = endpoints.update(
                WebhookEndpointId.of(endpointId),
                new ManageEndpointUseCase.Change(request.url(), request.description(), request.eventTypes(), status),
                required(ifMatch));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(endpoint.version())).body(EndpointResponse.of(endpoint));
    }

    /** Deletes the endpoint: it stops receiving and leaves every list, while its delivery history stays readable. */
    @DeleteMapping("/webhook-endpoints/{endpointId}")
    @RequiresPermission(BookingPermissions.WEBHOOK_MANAGE)
    @ConditionalUpdate
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable String endpointId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        endpoints.delete(WebhookEndpointId.of(endpointId), required(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhook-endpoints/{endpointId}/rotate-secret")
    @RequiresPermission(BookingPermissions.WEBHOOK_MANAGE)
    public EndpointWithSecretResponse rotateSecret(
            @PathVariable String endpointId,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        ManageEndpointUseCase.Rotated rotated = endpoints.rotateSecret(WebhookEndpointId.of(endpointId));
        return new EndpointWithSecretResponse(EndpointResponse.of(rotated.endpoint()), rotated.signingSecret());
    }

    /** A conditional change without its version would silently overwrite another caller's, so it answers 428. */
    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
