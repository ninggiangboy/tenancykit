package dev.ngb.workflow.triggers.adapter.manual.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.shared.secret.Hashes;
import dev.ngb.workflow.triggers.application.trigger.command.StartManualUseCase;
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
@Profile("api")
@RestController
@RequestMapping("/api/v1")
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
