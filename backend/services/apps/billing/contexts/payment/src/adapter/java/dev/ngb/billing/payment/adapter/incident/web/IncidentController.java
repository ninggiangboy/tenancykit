package dev.ngb.billing.payment.adapter.incident.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.payment.application.incident.query.IncidentListing;
import dev.ngb.billing.payment.application.incident.query.IncidentQueries;
import dev.ngb.billing.payment.application.incident.query.IncidentView;
import dev.ngb.billing.payment.application.stripe.command.ManageStripeEventUseCase;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
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

/** Stripe incidents: problems that need a person to look, and resolving them. */
@Tag(
        name = "Stripe incidents",
        description =
                "Problems with Stripe that need a person to look, and resolving them.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers(Caller.TENANT_USER)
public class IncidentController {

    public record IncidentResponse(
            String id, String kind, String subject, String detail, int occurrences, Instant createdAt, Instant lastSeenAt, Instant resolvedAt) {
        static IncidentResponse ofView(IncidentView i) {
            return new IncidentResponse(i.id().toString(), i.kind().name(), i.subject(), i.detail(), i.occurrences(), i.createdAt(), i.lastSeenAt(),
                    i.resolvedAt());
        }
    }

    private final IncidentQueries incidentQueries;
    private final ManageStripeEventUseCase eventAdmin;
    private final CurrentActor currentActor;

    public IncidentController(IncidentQueries incidentQueries, ManageStripeEventUseCase eventAdmin, CurrentActor currentActor) {
        this.incidentQueries = incidentQueries;
        this.eventAdmin = eventAdmin;
        this.currentActor = currentActor;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link IncidentListing}. */
    @Operation(
            summary = "List Stripe incidents",
            description =
                    "Pages through the problems that need a person to look, such as a payment that disagrees with Stripe, "
                    + "with the list-query contract's filtering, sorting and paging.")
    @GetMapping("/stripe/incidents")
    @Listing(IncidentListing.class)
    @RequiresPermission(BillingPermissions.STRIPE_READ)
    public Page<IncidentResponse> incidents(ListQuery query) {
        return incidentQueries.list(query).map(IncidentResponse::ofView);
    }

    @Operation(
            summary = "Resolve a Stripe incident",
            description =
                    "Closes an incident once a person has dealt with it. Answers `204`.")
    @PostMapping("/stripe/incidents/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public void resolveIncident(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        eventAdmin.resolveIncident(Audit.of(currentActor.get().auditName(), requestId), UUID.fromString(id));
    }
}
