package dev.ngb.platform.access.adapter.role.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.role.query.RoleListing;
import dev.ngb.platform.access.application.role.query.RoleQueries;
import dev.ngb.platform.access.application.role.query.RoleView;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.Set;
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

/** Platform administration of roles and their assignments. */
@RestController
@RequestMapping("/api/v1/roles")
class RoleController {

    record CreateRoleRequest(String name, Set<String> permissions) {}

    record PermissionsRequest(Set<String> permissions) {}

    record RenameRequest(String name) {}

    record RoleResponse(UUID id, UUID tenantId, String scope, String name, boolean system, Set<String> permissions, long version) {

        static RoleResponse of(Role role) {
            return new RoleResponse(
                    role.id().value(), role.tenantId().value(), role.scope().name(), role.name(), role.isSystem(),
                    role.permissions(), role.version());
        }

        static RoleResponse ofView(RoleView view) {
            return new RoleResponse(
                    view.id(), view.tenantId(), view.scope(), view.name(), view.system(), view.permissions(), view.version());
        }
    }

    private final CreateRoleUseCase createRole;
    private final ManageRoleUseCase manageRole;
    private final RoleQueries queries;
    private final CurrentActor currentActor;

    RoleController(CreateRoleUseCase createRole, ManageRoleUseCase manageRole, RoleQueries queries, CurrentActor currentActor) {
        this.createRole = createRole;
        this.manageRole = manageRole;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.ROLE_MANAGE)
    RoleResponse create(
            @RequestBody CreateRoleRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Actor actor = currentActor.get();
        return RoleResponse.of(createRole.execute(actor, requestId, new CreateRoleUseCase.Command(
                actor.tenantId(), actor.scope(), request.name(), request.permissions())));
    }

    /**
     * Filter, sort and paging follow the list-query contract; the documented parameters come from {@link RoleListing}.
     * {@code tenant_id} is a filter rather than a parameter, and a Tenant-scope caller never sees another Tenant.
     */
    @GetMapping
    @Listing(RoleListing.class)
    @RequiresPermission(PlatformPermissions.ROLE_READ)
    Page<RoleResponse> list(ListQuery query) {
        return queries.list(query, tenantScope()).map(RoleResponse::ofView);
    }

    /**
     * The Tenant a Tenant-scope caller may list, or null for a Platform administrator, who administers every Tenant
     * and names one with {@code filter[tenant_id][eq]}. The read applies it as a condition, so a filter on another
     * Tenant narrows the page to no row instead of widening it.
     */
    private UUID tenantScope() {
        Actor actor = currentActor.get();
        return actor.scope() == PermissionScope.TENANT ? actor.tenantId().value() : null;
    }

    @PutMapping("/{id}/permissions")
    @RequiresPermission(PlatformPermissions.ROLE_MANAGE)
    ResponseEntity<RoleResponse> replacePermissions(
            @PathVariable UUID id, @RequestBody PermissionsRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Role role = manageRole.execute(currentActor.get(), requestId, new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.REPLACE_PERMISSIONS, request.permissions(), null, null,
                ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(role.version())).body(RoleResponse.of(role));
    }

    @PutMapping("/{id}")
    @RequiresPermission(PlatformPermissions.ROLE_MANAGE)
    ResponseEntity<RoleResponse> rename(
            @PathVariable UUID id, @RequestBody RenameRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Role role = manageRole.execute(currentActor.get(), requestId, new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.RENAME, Set.of(), request.name(), null,
                ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(role.version())).body(RoleResponse.of(role));
    }

    @PostMapping("/{id}/users/{userId}")
    @RequiresPermission(PlatformPermissions.ROLE_MANAGE)
    RoleResponse assign(
            @PathVariable UUID id, @PathVariable UUID userId,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return RoleResponse.of(manageRole.execute(currentActor.get(), requestId, new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, new UserId(userId), ExpectedVersion.any())));
    }

    @DeleteMapping("/{id}/users/{userId}")
    @RequiresPermission(PlatformPermissions.ROLE_MANAGE)
    RoleResponse unassign(
            @PathVariable UUID id, @PathVariable UUID userId,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return RoleResponse.of(manageRole.execute(currentActor.get(), requestId, new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.UNASSIGN, Set.of(), null, new UserId(userId), ExpectedVersion.any())));
    }
}
