package dev.ngb.email.audience.adapter.group.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.audience.application.audience.query.AudienceQueries;
import dev.ngb.email.audience.application.audience.query.GroupView;
import dev.ngb.email.audience.application.audience.query.ReceiverView;
import dev.ngb.email.audience.application.group.command.CreateGroupUseCase;
import dev.ngb.email.audience.application.group.command.DeleteGroupUseCase;
import dev.ngb.email.audience.application.group.command.ManageMembersUseCase;
import dev.ngb.email.audience.application.group.command.UpdateGroupUseCase;
import dev.ngb.email.audience.domain.group.ReceiverGroup;
import dev.ngb.email.shared.EmailPermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Static groups and their membership. */
@RestController
@RequestMapping("/api/v1/groups")
class GroupController {

    private final CreateGroupUseCase create;
    private final UpdateGroupUseCase update;
    private final DeleteGroupUseCase delete;
    private final ManageMembersUseCase members;
    private final AudienceQueries queries;

    GroupController(
            CreateGroupUseCase create,
            UpdateGroupUseCase update,
            DeleteGroupUseCase delete,
            ManageMembersUseCase members,
            AudienceQueries queries) {
        this.create = create;
        this.update = update;
        this.delete = delete;
        this.members = members;
        this.queries = queries;
    }

    @GetMapping
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    List<GroupResponse> list(
            @RequestParam(required = false) UUID after, @RequestParam(required = false) Integer limit) {
        return queries.groups(after, limit).stream().map(GroupResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    GroupResponse create(@RequestBody GroupRequest request) {
        return GroupResponse.of(create.execute(request.name()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    ResponseEntity<GroupResponse> get(@PathVariable UUID id) {
        GroupView view = queries.group(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(GroupResponse.of(view));
    }

    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    ResponseEntity<GroupResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody GroupRequest request) {
        ReceiverGroup group = update.execute(id, request.name(), ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(group.version()))
                .body(GroupResponse.of(group));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    @GetMapping("/{id}/members")
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    List<MemberResponse> members(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {
        return queries.members(id, after, limit).stream().map(MemberResponse::of).toList();
    }

    @PostMapping("/{id}/members")
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    MemberChange addMembers(@PathVariable UUID id, @RequestBody MemberIds request) {
        return new MemberChange(members.add(id, request.receiverIds()));
    }

    @DeleteMapping("/{id}/members")
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    MemberChange removeMembers(@PathVariable UUID id, @RequestBody MemberIds request) {
        return new MemberChange(members.remove(id, request.receiverIds()));
    }

    record GroupRequest(String name) {}

    record MemberIds(List<UUID> receiverIds) {}

    record MemberChange(int changed) {}

    record GroupResponse(UUID id, String name, long version, Instant createdAt, Instant updatedAt) {

        static GroupResponse of(ReceiverGroup group) {
            return new GroupResponse(
                    group.id(), group.name(), group.version(), group.createdAt(), group.updatedAt());
        }

        static GroupResponse of(GroupView view) {
            return new GroupResponse(view.id(), view.name(), view.version(), view.createdAt(), view.updatedAt());
        }
    }

    record MemberResponse(UUID id, String email, String firstName, String lastName) {

        static MemberResponse of(ReceiverView view) {
            return new MemberResponse(view.id(), view.email(), view.firstName(), view.lastName());
        }
    }
}
