package dev.ngb.booking.catalog.adapter.group.web;

import dev.ngb.booking.catalog.application.group.command.CreateGroupUseCase;
import dev.ngb.booking.catalog.application.group.command.PatchGroupUseCase;
import dev.ngb.booking.catalog.application.group.query.GroupMemberView;
import dev.ngb.booking.catalog.application.group.query.GroupQueries;
import dev.ngb.booking.catalog.application.group.query.GroupView;
import dev.ngb.booking.catalog.application.group.service.GroupRules;
import dev.ngb.booking.catalog.domain.group.GroupMember;
import dev.ngb.booking.catalog.domain.group.GroupStrategy;
import dev.ngb.booking.catalog.domain.group.ResourceGroup;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.shared.ConditionalRequests;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resource groups: a set of resources a requirement may be answered by, with the strategy that picks one and the
 * members themselves. The code is the Tenant's own and immutable; a PATCH replaces the strategy, the combine cap or the
 * whole member set, and is conditional on {@code If-Match}.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ResourceGroupController {

    public record GroupMemberRequest(UUID resourceId, Integer priority) {}

    public record GroupCreateRequest(
            String code, String name, GroupStrategy strategy, Integer maxCombine, List<GroupMemberRequest> members) {}

    public record GroupPatchRequest(
            String name, GroupStrategy strategy, Integer maxCombine, List<GroupMemberRequest> members) {}

    public record GroupMemberResponse(String resourceId, int priority) {

        static GroupMemberResponse of(GroupMember member) {
            return new GroupMemberResponse(member.resourceId().toString(), member.priority());
        }

        static GroupMemberResponse ofView(GroupMemberView member) {
            return new GroupMemberResponse(member.resourceId().toString(), member.priority());
        }
    }

    public record GroupResponse(
            String id,
            String code,
            String name,
            String strategy,
            int maxCombine,
            List<GroupMemberResponse> members,
            long version) {

        static GroupResponse of(ResourceGroup group) {
            return new GroupResponse(
                    group.id().toString(),
                    group.code(),
                    group.name(),
                    group.strategy().name(),
                    group.maxCombine(),
                    group.members().stream().map(GroupMemberResponse::of).toList(),
                    group.version());
        }

        static GroupResponse ofView(GroupView view) {
            return new GroupResponse(
                    view.id().toString(),
                    view.code(),
                    view.name(),
                    view.strategy(),
                    view.maxCombine(),
                    view.members().stream().map(GroupMemberResponse::ofView).toList(),
                    view.version());
        }
    }

    /** The list contract for groups is a plain list: every group of the tenant, newest first. */
    public record GroupPage(List<GroupResponse> data) {}

    private final CreateGroupUseCase creates;
    private final PatchGroupUseCase patches;
    private final GroupQueries groups;

    public ResourceGroupController(CreateGroupUseCase creates, PatchGroupUseCase patches, GroupQueries groups) {
        this.creates = creates;
        this.patches = patches;
        this.groups = groups;
    }

    @GetMapping("/resource-groups")
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public GroupPage listGroups() {
        return new GroupPage(groups.list().stream().map(GroupResponse::ofView).toList());
    }

    @PostMapping("/resource-groups")
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<GroupResponse> createGroup(@RequestBody GroupCreateRequest request) {
        ResourceGroup group = creates.execute(new CreateGroupUseCase.Command(
                request.code(), request.name(), request.strategy(), request.maxCombine(), members(request.members())));
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.of(group));
    }

    @GetMapping("/resource-groups/{groupId}")
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public GroupResponse getGroup(@PathVariable String groupId) {
        return GroupResponse.ofView(groups.get(UUID.fromString(groupId)));
    }

    /** Changes only the properties that are present; the version the caller pinned must still be the current one. */
    @PatchMapping("/resource-groups/{groupId}")
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public GroupResponse patchGroup(
            @PathVariable String groupId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody GroupPatchRequest request) {
        ResourceGroup group = patches.execute(
                UUID.fromString(groupId),
                new PatchGroupUseCase.Change(
                        request.name(), request.strategy(), request.maxCombine(), members(request.members())),
                ConditionalRequests.requireIfMatch(ifMatch));
        return GroupResponse.of(group);
    }

    /** A null member list means "leave the set alone"; an empty one clears it. */
    private static List<GroupMember> members(List<GroupMemberRequest> raw) {
        if (raw == null) {
            return null;
        }
        List<GroupMember> members = new ArrayList<>(raw.size());
        for (GroupMemberRequest member : raw) {
            members.add(GroupRules.member(member.resourceId(), member.priority()));
        }
        return members;
    }
}
