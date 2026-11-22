package dev.ngb.workflow.runtime.adapter.instance.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.runtime.application.runtime.command.CancelInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.ReplayInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.RetryInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceStepsQueries;
import dev.ngb.workflow.runtime.application.runtime.query.StepRunView;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.shared.WorkflowPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an operator does with an instance that stopped: retry the failed step, replay the input as a new instance, cancel
 * it, and read the recorded timeline. The timeline is redacted: a step's request never leaves this API.
 */
@Tag(
        name = "Instance operations",
        description =
                "What an operator does with an instance that stopped: retry, replay, cancel and read its timeline.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class InstanceOperationsController {

    public record RetryRequest(String nodeId) {}

    public record InstanceStateResponse(String id, String status, String failureReason) {

        static InstanceStateResponse of(Instance instance) {
            return new InstanceStateResponse(
                    instance.id().toString(), instance.status().name(), instance.failureReason());
        }
    }

    private final RetryInstanceUseCase retry;
    private final ReplayInstanceUseCase replay;
    private final CancelInstanceUseCase cancel;
    private final InstanceStepsQueries steps;

    public InstanceOperationsController(
            RetryInstanceUseCase retry,
            ReplayInstanceUseCase replay,
            CancelInstanceUseCase cancel,
            InstanceStepsQueries steps) {
        this.retry = retry;
        this.replay = replay;
        this.cancel = cancel;
        this.steps = steps;
    }

    @Operation(
            summary = "Retry a failed instance",
            description =
                    "Retries an instance that dead-lettered: the failed step becomes ready again with the same id and "
                    + "attempt, so it sends the same idempotency key, and the instance goes back to `RUNNING` with no "
                    + "failure reason. `nodeId` names the step when needed. Requires `workflow:instance:manage`.")
    @PostMapping("/instances/{id}/retry")
    @RequiresPermission(WorkflowPermissions.INSTANCE_MANAGE)
    public ResponseEntity<InstanceStateResponse> retry(
            @PathVariable String id, @RequestBody(required = false) RetryRequest request) {
        Instance instance = retry.execute(new RetryInstanceUseCase.Command(
                UUID.fromString(id), request == null ? null : request.nodeId()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(instance.version()))
                .body(InstanceStateResponse.of(instance));
    }

    @Operation(
            summary = "Replay a finished instance",
            description =
                    "Starts a new instance from the same input and the same pinned version, and records the instance it "
                    + "was replayed from. The old instance stays as it was, so the pair is the comparison. Requires "
                    + "`workflow:instance:manage`.")
    @PostMapping("/instances/{id}/replay")
    @RequiresPermission(WorkflowPermissions.INSTANCE_MANAGE)
    public ResponseEntity<InstanceStateResponse> replay(@PathVariable String id) {
        Instance instance = replay.execute(new ReplayInstanceUseCase.Command(UUID.fromString(id)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(instance.version()))
                .body(InstanceStateResponse.of(instance));
    }

    @Operation(
            summary = "Cancel an instance",
            description =
                    "Cancels an instance an operator no longer wants: its live steps are cancelled, no timer or event can "
                    + "resume it, its open tasks are cancelled and it finishes `CANCELLED`. With `compensate=true` the "
                    + "completed steps are rolled back first. Requires `workflow:instance:manage`.")
    @PostMapping("/instances/{id}/cancel")
    @RequiresPermission(WorkflowPermissions.INSTANCE_MANAGE)
    public ResponseEntity<InstanceStateResponse> cancel(
            @PathVariable String id, @RequestParam(value = "compensate", defaultValue = "false") boolean compensate) {
        Instance instance = cancel.execute(new CancelInstanceUseCase.Command(UUID.fromString(id), compensate));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(instance.version()))
                .body(InstanceStateResponse.of(instance));
    }

    @Operation(
            summary = "List an instance's steps",
            description =
                    "Returns the recorded timeline of the instance, one entry per step run. Requests and responses are "
                    + "redacted: a step's request never leaves this API. Requires `workflow:instance:read`.")
    @GetMapping("/instances/{id}/steps")
    @RequiresPermission(WorkflowPermissions.INSTANCE_READ)
    public List<StepRunView> steps(@PathVariable String id) {
        return steps.timeline(UUID.fromString(id));
    }
}
