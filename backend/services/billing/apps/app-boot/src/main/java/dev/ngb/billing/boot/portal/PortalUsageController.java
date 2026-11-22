package dev.ngb.billing.boot.portal;

import dev.ngb.billing.session.api.portal.PortalPermission;
import dev.ngb.billing.session.api.portal.RequiresPortalPermission;
import dev.ngb.billing.usage.api.lookup.UsageTotal;
import dev.ngb.billing.usage.application.usage.query.UsageEventListing;
import dev.ngb.billing.usage.application.usage.query.UsageEventView;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Usage reported for the session's customer: event history and per-metric totals. */
@Tag(
        name = "Portal: usage",
        description =
                "Usage reported for the session's customer.")
@Profile("api")
@ConditionalOnProperty(name = "billing.portal.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/portal/api/usage")
public class PortalUsageController {

    public record UsageEventResponse(String id, String metric, String quantity, String unit, Instant occurredAt, String resourceRef) {
        static UsageEventResponse of(UsageEventView event) {
            return new UsageEventResponse(event.id().toString(), event.metric(), event.quantity().toPlainString(), event.unit(), event.occurredAt(),
                    event.resourceRef());
        }
    }

    public record UsageTotalResponse(String metric, String unit, String quantity, long events) {
        static UsageTotalResponse of(UsageTotal total) {
            return new UsageTotalResponse(total.metric(), total.unit(), total.quantity().stripTrailingZeros().toPlainString(), total.events());
        }
    }

    private final PortalReadModel portal;

    public PortalUsageController(PortalReadModel portal) {
        this.portal = portal;
    }

    /** Filter, sort and paging follow the list-query contract; the customer is fixed by the portal session. */
    @Operation(
            summary = "List the customer's usage events",
            description =
                    "Pages through the usage reported for the portal session's customer, with the list-query contract's "
                    + "filtering, sorting and paging. The customer is fixed by the portal session, so no other customer's "
                    + "data is reachable.")
    @GetMapping("/history")
    @Listing(UsageEventListing.class)
    @RequiresPortalPermission(PortalPermission.USAGE_VIEW)
    public Page<UsageEventResponse> history(ListQuery query) {
        return portal.usage(query).map(UsageEventResponse::of);
    }

    @Operation(
            summary = "Get the customer's usage totals",
            description =
                    "Returns the quantity and number of events per metric over `[from, to)`, optionally for one `metric`. "
                    + "The customer is fixed by the portal session, so no other customer's data is reachable.")
    @GetMapping("/totals")
    @RequiresPortalPermission(PortalPermission.USAGE_VIEW)
    public List<UsageTotalResponse> totals(
            @RequestParam(required = false) String metric, @RequestParam Instant from, @RequestParam Instant to) {
        return portal.usageTotals(metric, from, to).stream().map(UsageTotalResponse::of).toList();
    }
}
