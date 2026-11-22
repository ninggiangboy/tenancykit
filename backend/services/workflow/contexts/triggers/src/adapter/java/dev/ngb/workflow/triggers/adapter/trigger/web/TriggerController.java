package dev.ngb.workflow.triggers.adapter.trigger.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.triggers.application.trigger.command.ManageTriggerUseCase;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerListing;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerQueries;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerView;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ways a Tenant starts a workflow: create a trigger, read one, list them, and disable or re-filter one.
 *
 * <p>A signed trigger answers with its signing secret exactly once, at creation; every later read shows the trigger
 * without it. A change is conditional on {@code If-Match} like every other resource.
 */
@Tag(
        name = "Triggers",
        description =
                "The ways a tenant starts a workflow: signed events from apps or external systems, and schedules.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class TriggerController {

    public record CreateTriggerRequest(
            String name,
            String kind,
            String source,
            String eventType,
            UUID definitionId,
            Object filter,
            Object correlation,
            Object input,
            String cron,
            String timezone) {}

    public record UpdateTriggerRequest(
            String name, String eventType, Object filter, Object correlation, Object input, Boolean enabled) {}

    public record TriggerResponse(
            String id,
            String name,
            String kind,
            String source,
            String eventType,
            String definitionId,
            Object filter,
            Object correlation,
            Object input,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {

        static TriggerResponse of(Trigger trigger) {
            return new TriggerResponse(
                    trigger.id().toString(),
                    trigger.name(),
                    trigger.kind().name(),
                    trigger.source(),
                    trigger.eventType(),
                    trigger.definitionId().toString(),
                    trigger.filter(),
                    trigger.correlation(),
                    trigger.input(),
                    trigger.enabled(),
                    trigger.createdAt(),
                    trigger.updatedAt());
        }

        static TriggerResponse ofView(TriggerView view) {
            return new TriggerResponse(
                    view.id().toString(),
                    view.name(),
                    view.kind().name(),
                    view.source(),
                    view.eventType(),
                    view.definitionId().toString(),
                    view.filter(),
                    view.correlation(),
                    view.input(),
                    view.enabled(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    /** The one response that carries the plaintext signing secret. */
    public record TriggerWithSecretResponse(TriggerResponse trigger, String signingSecret) {}

    private final ManageTriggerUseCase triggers;
    private final TriggerQueries triggerQueries;

    public TriggerController(ManageTriggerUseCase triggers, TriggerQueries triggerQueries) {
        this.triggers = triggers;
        this.triggerQueries = triggerQueries;
    }

    @Operation(
            summary = "Create a trigger",
            description =
                    "Creates a way to start a workflow: a `kind` (`APP` for a suite app's signed webhook, "
                    + "`EXTERNAL` for an outside system's signed webhook, or `SCHEDULE` for a cron schedule), its "
                    + "`source` and `eventType`, the definition to start, an optional `filter`, `correlation` and "
                    + "`input`. A signed trigger's secret is in this response and never again. A schedule trigger's first tick is after now, "
                    + "so one created while the worker was down does not fire every missed tick. Requires "
                    + "`workflow:trigger:manage`.")
    @PostMapping("/triggers")
    @RequiresPermission(WorkflowPermissions.TRIGGER_MANAGE)
    public ResponseEntity<TriggerWithSecretResponse> createTrigger(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CreateTriggerRequest request) {
        ManageTriggerUseCase.Created created = triggers.create(new ManageTriggerUseCase.CreateCommand(
                request.name(),
                request.kind(),
                request.source(),
                request.eventType(),
                request.definitionId(),
                request.filter(),
                request.correlation(),
                request.input(),
                request.cron(),
                request.timezone()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(created.trigger().version()))
                .body(new TriggerWithSecretResponse(TriggerResponse.of(created.trigger()), created.secret()));
    }

    @Operation(
            summary = "List triggers",
            description =
                    "Pages through the tenant's triggers, with the list-query contract's filtering, sorting and paging. "
                    + "Requires `workflow:trigger:manage`.")
    @GetMapping("/triggers")
    @Listing(TriggerListing.class)
    @RequiresPermission(WorkflowPermissions.TRIGGER_MANAGE)
    public Page<TriggerResponse> listTriggers(ListQuery query) {
        return triggerQueries.list(query).map(TriggerResponse::ofView);
    }

    @Operation(
            summary = "Get a trigger",
            description =
                    "Returns one trigger without its secret; its version is also the response's `ETag`. An unknown id "
                    + "answers `404`. Requires `workflow:trigger:manage`.")
    @GetMapping("/triggers/{id}")
    @RequiresPermission(WorkflowPermissions.TRIGGER_MANAGE)
    public ResponseEntity<TriggerResponse> getTrigger(@PathVariable String id) {
        TriggerView trigger = triggerQueries.get(UUID.fromString(id));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(trigger.version()))
                .body(TriggerResponse.ofView(trigger));
    }

    @Operation(
            summary = "Update or disable a trigger",
            description =
                    "Changes the trigger's name, event type, filter, correlation or input, or disables it with "
                    + "`enabled=false`. Pass the current `ETag` in `If-Match`; a stale version is refused with `412`. "
                    + "Requires `workflow:trigger:manage`.")
    @PutMapping("/triggers/{id}")
    @ConditionalUpdate
    @RequiresPermission(WorkflowPermissions.TRIGGER_MANAGE)
    public ResponseEntity<TriggerResponse> updateTrigger(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody UpdateTriggerRequest request) {
        Trigger trigger = triggers.update(new ManageTriggerUseCase.UpdateCommand(
                UUID.fromString(id),
                required(ifMatch),
                request.name(),
                request.eventType(),
                request.filter(),
                request.correlation(),
                request.input(),
                request.enabled()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(trigger.version()))
                .body(TriggerResponse.of(trigger));
    }

    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new dev.ngb.workflow.triggers.application.trigger.exception.PreconditionRequiredException(
                    "If-Match is required: send the version this change is based on.");
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
