package dev.ngb.billing.session.adapter.portal.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.billing.session.api.portal.PortalPermission;
import dev.ngb.billing.session.application.portal.command.OpenBillingSessionUseCase;
import dev.ngb.billing.session.application.portal.command.RevokeBillingSessionUseCase;
import dev.ngb.billing.session.domain.portal.BillingSession;
import dev.ngb.billing.session.domain.portal.BillingSessionId;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant System side of a Billing Session: create one for a mapped customer, and revoke a session it issued.
 * The exchange token is returned once, inside the portal URL.
 */
@Tag(
        name = "Billing sessions",
        description =
                "The Tenant System side of the customer portal: create a billing session and revoke one.")
@Profile("api")
@ConditionalOnProperty(name = "billing.portal.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/billing-sessions")
public class BillingSessionController {

    public record CreateRequest(String customerId, String returnUrl, List<String> permissions, Long expiresInSeconds) {}

    public record CreateResponse(String id, String customerId, String portalUrl, List<String> permissions, Instant expiresAt) {}

    private final OpenBillingSessionUseCase open;
    private final RevokeBillingSessionUseCase revoke;
    private final CurrentActor currentActor;

    public BillingSessionController(OpenBillingSessionUseCase open, RevokeBillingSessionUseCase revoke, CurrentActor currentActor) {
        this.open = open;
        this.revoke = revoke;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Create a billing session",
            description =
                    "Issues a portal session for one customer (`customerId`) to the Tenant System that asked, with a "
                    + "`returnUrl` from the tenant's allowlist, the `permissions` it grants and a lifetime. The answer "
                    + "carries the `portalUrl` to open in the customer's browser; its single-use exchange token is shown "
                    + "once there, and only its digest is stored.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BillingPermissions.BILLING_SESSION_CREATE)
    public CreateResponse create(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CreateRequest request,
            HttpServletRequest http) {
        if (request.customerId() == null || request.returnUrl() == null) {
            throw new IllegalArgumentException("customerId and returnUrl are required");
        }
        Actor actor = currentActor.get();
        Set<PortalPermission> permissions = request.permissions() == null
                ? null
                : PortalPermission.fromCodes(Set.copyOf(request.permissions()));
        Duration ttl = request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds());
        OpenBillingSessionUseCase.Result result = open.execute(new OpenBillingSessionUseCase.Command(
                CustomerId.of(request.customerId()), request.returnUrl(), permissions, ttl, currentActor.caller(),
                Audit.of(actor.auditName(), requestId)));
        BillingSession session = result.session();
        return new CreateResponse(
                session.id().toString(),
                session.customerId().toString(),
                portalUrl(http, result.exchangeToken()),
                session.permissions().stream().map(PortalPermission::code).sorted().toList(),
                session.expiresAt());
    }

    @Operation(
            summary = "Revoke a billing session",
            description =
                    "Revokes the session at once. A Tenant System may revoke only the sessions it issued; a Tenant User "
                    + "may revoke any.")
    @PostMapping("/{id}/revoke")
    @RequiresPermission(BillingPermissions.BILLING_SESSION_CREATE)
    public ResponseEntity<Void> revoke(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Actor actor = currentActor.get();
        UUID tenantSystemId = currentActor.caller();
        revoke.execute(BillingSessionId.of(id), tenantSystemId, Audit.of(actor.auditName(), requestId));
        return ResponseEntity.noContent().build();
    }

    /** The portal URL lives on the hostname the request arrived on, which is the tenant's own hostname. */
    private static String portalUrl(HttpServletRequest request, String token) {
        StringBuilder origin = new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
        if (!defaultPort) {
            origin.append(':').append(port);
        }
        return origin.append("/portal/session#token=").append(token).toString();
    }
}
