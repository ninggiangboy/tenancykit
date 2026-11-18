package dev.ngb.workflow.tasks.adapter.task.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.tasks.application.task.command.ClaimTaskUseCase;
import dev.ngb.workflow.tasks.application.task.command.CompleteTaskUseCase;
import dev.ngb.workflow.tasks.application.task.command.ReassignTaskUseCase;
import dev.ngb.workflow.tasks.application.task.exception.PreconditionRequiredException;
import dev.ngb.workflow.tasks.application.task.query.TaskQueries;
import dev.ngb.workflow.tasks.application.task.query.TaskView;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The human-task inbox. A task is visible to the user it names or to a member of the group it was assigned to; anyone
 * else is answered "not found", because a 403 would confirm that a task exists.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    public record CompleteRequest(String decision, Object output, String completedBy) {}

    public record ReassignRequest(UUID user, String group) {}

    private final TaskQueries queries;
    private final ClaimTaskUseCase claim;
    private final CompleteTaskUseCase complete;
    private final ReassignTaskUseCase reassign;

    public TaskController(
            TaskQueries queries,
            ClaimTaskUseCase claim,
            CompleteTaskUseCase complete,
            ReassignTaskUseCase reassign) {
        this.queries = queries;
        this.claim = claim;
        this.complete = complete;
        this.reassign = reassign;
    }

    @GetMapping("/tasks")
    @RequiresPermission(WorkflowPermissions.TASK_READ)
    public List<TaskView> inbox(@RequestParam(value = "status", required = false) String status) {
        return queries.inbox(status);
    }

    @GetMapping("/tasks/{id}")
    @RequiresPermission(WorkflowPermissions.TASK_READ)
    public ResponseEntity<TaskView> get(@PathVariable String id) {
        TaskView view = queries.get(UUID.fromString(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(view);
    }

    @PostMapping("/tasks/{id}/claim")
    @RequiresPermission(WorkflowPermissions.TASK_COMPLETE)
    public ResponseEntity<TaskView> claim(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Task task = claim.execute(new ClaimTaskUseCase.Command(UUID.fromString(id), required(ifMatch)));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(task.version()))
                .body(queries.get(task.id()));
    }

    @PostMapping("/tasks/{id}/complete")
    @RequiresPermission(WorkflowPermissions.TASK_COMPLETE)
    public ResponseEntity<TaskView> complete(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CompleteRequest request) {
        Task task = complete.execute(new CompleteTaskUseCase.Command(
                UUID.fromString(id),
                required(ifMatch),
                request.decision(),
                request.output(),
                request.completedBy()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(task.version()))
                .body(queries.get(task.id()));
    }

    @PostMapping("/tasks/{id}/reassign")
    @RequiresPermission(WorkflowPermissions.TASK_COMPLETE)
    public ResponseEntity<TaskView> reassign(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ReassignRequest request) {
        Task task = reassign.execute(new ReassignTaskUseCase.Command(
                UUID.fromString(id), required(ifMatch), request.user(), request.group()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(task.version()))
                .body(queries.get(task.id()));
    }

    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required: send the version the task was read at.");
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
