package dev.ngb.mailroom.audience.adapter.group.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.audience.application.audience.query.AudienceQueries;
import dev.ngb.mailroom.audience.application.audience.query.GroupListing;
import dev.ngb.mailroom.audience.application.audience.query.GroupView;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverListing;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverView;
import dev.ngb.mailroom.audience.application.group.command.CreateGroupUseCase;
import dev.ngb.mailroom.audience.application.group.command.DeleteGroupUseCase;
import dev.ngb.mailroom.audience.application.group.command.ManageMembersUseCase;
import dev.ngb.mailroom.audience.application.group.command.UpdateGroupUseCase;
import dev.ngb.mailroom.audience.domain.group.ReceiverGroup;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Receiver groups",
        description =
                "Static groups of receivers and their membership.")
@RestController
@RequestMapping("/groups")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
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

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link GroupListing}. */
    @Operation(
            summary = "List receiver groups",
            description =
                    "Pages through the tenant's static receiver groups, with the list-query contract's filtering, sorting "
                    + "and paging.")
    @GetMapping
    @Listing(GroupListing.class)
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    Page<GroupResponse> list(ListQuery query) {
        return queries.groups(query).map(GroupResponse::of);
    }

    @Operation(
            summary = "Create a receiver group",
            description =
                    "Creates an empty static group with a name. Add receivers to it with `POST "
                    + "/api/v1/groups/{id}/members`. Answers `201`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    GroupResponse create(@RequestBody GroupRequest request) {
        return GroupResponse.of(create.execute(request.name()));
    }

    @Operation(
            summary = "Get a receiver group",
            description =
                    "Returns one group; its version is also the response's `ETag`. An unknown id answers `404`. Requires "
                    + "`mailroom:receiver:read`.")
    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    ResponseEntity<GroupResponse> get(@PathVariable UUID id) {
        GroupView view = queries.group(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(GroupResponse.of(view));
    }

    @Operation(
            summary = "Rename a receiver group",
            description =
                    "Changes the group's name. Pass the current `ETag` in `If-Match`; a stale version is refused. ")
    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    ResponseEntity<GroupResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody GroupRequest request) {
        ReceiverGroup group = update.execute(id, request.name(), ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(group.version()))
                .body(GroupResponse.of(group));
    }

    @Operation(
            summary = "Delete a receiver group",
            description =
                    "Deletes the group and its memberships; the receivers themselves stay. Pass the current `ETag` in "
                    + "`If-Match`; a stale version is refused. Answers `204`.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    /** The group is fixed by the path; filter, sort and paging follow the list-query contract. */
    @Operation(
            summary = "List the members of a receiver group",
            description =
                    "Pages through the receivers in the group named by the path, with the list-query contract's "
                    + "filtering, sorting and paging.")
    @GetMapping("/{id}/members")
    @Listing(ReceiverListing.class)
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    Page<MemberResponse> members(@PathVariable UUID id, ListQuery query) {
        return queries.members(id, query).map(MemberResponse::of);
    }

    @Operation(
            summary = "Add receivers to a group",
            description =
                    "Adds up to 1,000 receivers, named by `receiverIds`, to the group. A receiver that belongs to another "
                    + "tenant is refused. Answers how many memberships changed.")
    @PostMapping("/{id}/members")
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    MemberChange addMembers(@PathVariable UUID id, @RequestBody MemberIds request) {
        return new MemberChange(members.add(id, request.receiverIds()));
    }

    @Operation(
            summary = "Remove receivers from a group",
            description =
                    "Removes up to 1,000 receivers, named by `receiverIds`, from the group; the receivers themselves "
                    + "stay. Answers how many memberships changed.")
    @DeleteMapping("/{id}/members")
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
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
