package dev.ngb.workflow.triggers.adapter.manual.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.shared.secret.Hashes;
import dev.ngb.workflow.triggers.application.trigger.command.StartManualUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts a workflow from a manual call.
 *
 * <p>The definition is resolved to its newest published version. A caller that sends {@code Idempotency-Key} gets the
 * first instance back for a repeated identical call, and a 409 when the same key arrives with another body.
 */
@Tag(
        name = "Manual start",
        description =
                "Starts a workflow instance from a manual call.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class InstanceController {

    public record StartInstanceRequest(UUID definition, Object input, String correlationKey) {}

    public record StartedInstanceResponse(String id, String status) {}

    private final StartManualUseCase start;
    private final CurrentActor currentActor;
    private final JsonMapper json;

    public InstanceController(StartManualUseCase start, CurrentActor currentActor, JsonMapper json) {
        this.start = start;
        this.currentActor = currentActor;
        this.json = json;
    }

    @Operation(
            summary = "Start a workflow manually",
            description =
                    "Starts an instance of `definition` from its newest published version, with `input` and an optional "
                    + "`correlationKey`. The tenant comes from the request, so a caller can never start another tenant's "
                    + "workflow. A repeated call with the same `Idempotency-Key` returns the first instance; the same key "
                    + "with another body answers `409`. Answers `201`.")
    @PostMapping("/instances")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(WorkflowPermissions.INSTANCE_MANAGE)
    public StartedInstanceResponse startInstance(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody StartInstanceRequest request) {
        String hash = idempotencyKey == null ? null : Hashes.sha256(json.writeValueAsString(request));
        var started = start.execute(new StartManualUseCase.Command(
                request.definition(),
                request.input(),
                request.correlationKey(),
                idempotencyKey,
                hash,
                currentActor.get().auditName()));
        return new StartedInstanceResponse(started.instanceId().toString(), started.status());
    }
}
