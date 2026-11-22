package dev.ngb.workflow.tasks.adapter.task.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.tasks.application.task.command.ManageTaskGroupUseCase;
import dev.ngb.workflow.tasks.application.task.exception.PreconditionRequiredException;
import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The named lists a task may be assigned to. Members are active users of the replica when they are added. */
@Tag(
        name = "Task groups",
        description =
                "The named lists a human task may be assigned to.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class TaskGroupController {

    public record GroupRequest(String code, String name, Set<UUID> members) {}

    public record GroupResponse(String code, String name, List<String> members, long version) {

        static GroupResponse of(TaskGroup group, Set<UUID> members) {
            return new GroupResponse(
                    group.code(), group.name(), members.stream().map(UUID::toString).toList(), group.version());
        }
    }

    private final ManageTaskGroupUseCase groups;

    public TaskGroupController(ManageTaskGroupUseCase groups) {
        this.groups = groups;
    }

    @Operation(
            summary = "List task groups",
            description =
                    "Returns the named lists a task may be assigned to, with their members. Requires "
                    + "`workflow:task:read`.")
    @GetMapping("/task-groups")
    @RequiresPermission(WorkflowPermissions.TASK_READ)
    public List<GroupResponse> list() {
        return groups.all().stream()
                .map(group -> GroupResponse.of(group, groupMembers(group)))
                .toList();
    }

    @Operation(
            summary = "Create a task group",
            description =
                    "Creates a group with a `code`, a name and its `members`. Members must be active users when they are "
                    + "added. Answers `201`. Requires `workflow:task:manage`.")
    @PostMapping("/task-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(WorkflowPermissions.TASK_MANAGE)
    public GroupResponse create(@RequestBody GroupRequest request) {
        TaskGroup group = groups.create(
                new ManageTaskGroupUseCase.CreateCommand(request.code(), request.name(), request.members()));
        return GroupResponse.of(group, request.members() == null ? Set.of() : request.members());
    }

    @Operation(
            summary = "Update a task group",
            description =
                    "Replaces the group's name and members. A member disabled later keeps the group membership but loses "
                    + "access to the tasks, because every action re-reads the user's state. Pass the current `ETag` in "
                    + "`If-Match`; a stale version is refused with `412`. Requires `workflow:task:manage`.")
    @PutMapping("/task-groups/{code}")
    @RequiresPermission(WorkflowPermissions.TASK_MANAGE)
    public ResponseEntity<GroupResponse> update(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody GroupRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required: send the version this change is based on.");
        }
        TaskGroup group = groups.update(new ManageTaskGroupUseCase.UpdateCommand(
                code, ExpectedVersion.fromIfMatch(ifMatch), request.name(), request.members()));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(group.version()))
                .body(GroupResponse.of(group, request.members() == null ? Set.of() : request.members()));
    }

    @Operation(
            summary = "Delete a task group",
            description =
                    "Deletes the group. A workflow node that is assigned to it fails with a readable reason rather than "
                    + "creating a task nobody can see. Answers `204`. Requires `workflow:task:manage`.")
    @DeleteMapping("/task-groups/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(WorkflowPermissions.TASK_MANAGE)
    public void delete(@PathVariable String code) {
        groups.delete(code);
    }

    private Set<UUID> groupMembers(TaskGroup group) {
        // The list view resolves members through the same use case's repository; kept out of the API port for brevity.
        return groups.membersOf(group);
    }
}
