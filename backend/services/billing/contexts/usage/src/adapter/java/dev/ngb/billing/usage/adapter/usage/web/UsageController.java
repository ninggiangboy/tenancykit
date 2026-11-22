package dev.ngb.billing.usage.adapter.usage.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.usage.api.lookup.UsageEventId;
import dev.ngb.billing.usage.api.lookup.UsageTotal;
import dev.ngb.billing.usage.application.usage.command.RecordUsageUseCase;
import dev.ngb.billing.usage.application.usage.query.UsageEventListing;
import dev.ngb.billing.usage.application.usage.query.UsageEventView;
import dev.ngb.billing.usage.application.usage.query.UsageQueries;
import dev.ngb.billing.usage.domain.usage.UsageEvent;
import dev.ngb.billing.usage.domain.usage.UsageTrace;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usage reported by tenant systems. Each event carries its own {@code idempotencyKey}, so a retry of a single
 * event or of a whole batch never records anything twice. Quantities are decimal strings (or numbers) with up
 * to six decimal places, in the unit the metric's pricing plan defines.
 */
@Tag(
        name = "Usage",
        description =
                "Usage reported by tenant systems, idempotent per event, and its aggregates.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class UsageController {

    public record UsageEventRequest(
            String customerId, String metric, String quantity, String unit, Instant occurredAt, String idempotencyKey, String resourceRef,
            String sourceEventId) {}

    public record BatchRequest(List<UsageEventRequest> events) {}

    public record UsageEventResponse(
            String id, String customerId, String metric, String quantity, String unit, Instant occurredAt, Instant recordedAt, String idempotencyKey,
            String tenantSystemId, String resourceRef, String sourceEventId) {
        static UsageEventResponse of(UsageEvent e) {
            return new UsageEventResponse(e.id().toString(), e.customerId().toString(), e.metric(), e.quantity().toPlainString(), e.unit(), e.occurredAt(),
                    e.recordedAt(), e.idempotencyKey(), e.trace().tenantSystemId() == null ? null : e.trace().tenantSystemId().toString(),
                    e.trace().resourceRef(), e.trace().sourceEventId());
        }

        static UsageEventResponse ofView(UsageEventView v) {
            return new UsageEventResponse(v.id().toString(), v.customerId().toString(), v.metric(), v.quantity().toPlainString(), v.unit(), v.occurredAt(),
                    v.recordedAt(), v.idempotencyKey(), v.tenantSystemId() == null ? null : v.tenantSystemId().toString(),
                    v.resourceRef(), v.sourceEventId());
        }
    }

    public record RecordedResponse(UsageEventResponse event, boolean replayed) {
        static RecordedResponse of(RecordUsageUseCase.Recorded recorded) {
            return new RecordedResponse(UsageEventResponse.of(recorded.event()), recorded.replayed());
        }
    }

    public record BatchResponse(List<RecordedResponse> results) {}

    public record TotalResponse(String metric, String unit, String quantity, long events) {
        static TotalResponse of(UsageTotal t) {
            return new TotalResponse(t.metric(), t.unit(), t.quantity().toPlainString(), t.events());
        }
    }

    private final RecordUsageUseCase record;
    private final UsageQueries queries;
    private final CurrentActor currentActor;

    public UsageController(RecordUsageUseCase record, UsageQueries queries, CurrentActor currentActor) {
        this.record = record;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** 201 for a newly recorded event, 200 when the same event was already recorded. */
    @Operation(
            summary = "Record a usage event",
            description =
                    "Reports one usage event for a customer: the `metric`, a `quantity` with up to six decimal places in "
                    + "the unit the metric's plan defines, and when it `occurredAt`. Each event carries its own "
                    + "`idempotencyKey`, so a retry never records anything twice: `201` for a new event, `200` with the "
                    + "recorded event when it was already recorded, and `409` when the key arrives with different content.")
    @PostMapping("/usage-events")
    @RepeatableCreate(repeated = "The same event was already recorded; the recorded event is returned")
    @RequiresPermission(BillingPermissions.USAGE_RECORD)
    public ResponseEntity<RecordedResponse> recordOne(@RequestBody UsageEventRequest request) {
        RecordUsageUseCase.Recorded recorded = record.record(toItem(request, currentActor.caller()), currentActor.caller());
        return ResponseEntity.status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(RecordedResponse.of(recorded));
    }

    /** All-or-nothing: if any event is invalid or conflicts, none is stored. Up to 500 events. */
    @Operation(
            summary = "Record usage events in a batch",
            description =
                    "Reports up to 500 usage events at once, all-or-nothing: if any event is invalid or conflicts, none "
                    + "is stored.")
    @PostMapping("/usage-events/batch")
    @RequiresPermission(BillingPermissions.USAGE_RECORD)
    public BatchResponse recordBatch(@RequestBody BatchRequest request) {
        if (request.events() == null) {
            throw new IllegalArgumentException("events is required");
        }
        UUID caller = currentActor.caller();
        return new BatchResponse(record.record(request.events().stream().map(e -> toItem(e, caller)).toList(), caller).stream()
                .map(RecordedResponse::of).toList());
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link UsageEventListing}. */
    @Operation(
            summary = "List usage events",
            description =
                    "Pages through the recorded usage events, with the list-query contract's filtering, sorting and "
                    + "paging. A Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/usage-events")
    @Listing(UsageEventListing.class)
    @RequiresPermission(BillingPermissions.USAGE_READ)
    public Page<UsageEventResponse> history(ListQuery query) {
        return queries.history(query, currentActor.caller()).map(UsageEventResponse::ofView);
    }

    /** Quantity and event count per metric over {@code [from, to)}, for rating and for invoices. */
    @Operation(
            summary = "Get usage aggregates",
            description =
                    "Returns the quantity and the number of events per metric for a customer over `[from, to)`, "
                    + "optionally for the `metric` values given. Rating and invoices read the same totals.")
    @GetMapping("/usage-aggregates")
    @RequiresPermission(BillingPermissions.USAGE_READ)
    public List<TotalResponse> aggregates(
            @RequestParam String customerId, @RequestParam(required = false) List<String> metric, @RequestParam Instant from, @RequestParam Instant to) {
        Set<String> metrics = metric == null ? Set.of() : new HashSet<>(metric);
        return queries.totals(CustomerId.of(customerId), metrics, from, to, currentActor.caller()).stream().map(TotalResponse::of).toList();
    }

    private static RecordUsageUseCase.Item toItem(UsageEventRequest r, UUID caller) {
        if (r == null) {
            throw new IllegalArgumentException("a usage event is required");
        }
        if (r.customerId() == null || r.quantity() == null) {
            throw new IllegalArgumentException("customerId and quantity are required");
        }
        return new RecordUsageUseCase.Item(CustomerId.of(r.customerId()), r.metric(), new BigDecimal(r.quantity().strip()), r.unit(), r.occurredAt(),
                r.idempotencyKey(), new UsageTrace(caller == null ? null : new dev.ngb.core.id.TenantSystemId(caller), r.resourceRef(), r.sourceEventId()));
    }
}
