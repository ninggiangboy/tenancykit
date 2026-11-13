package dev.ngb.platform.access.adapter.role.web;

import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.role.query.RoleQueries;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.List;
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

    record CreateRequest(String name, Set<String> permissions) {}

    record PermissionsRequest(Set<String> permissions) {}

    record RenameRequest(String name) {}

    record RoleResponse(UUID id, UUID tenantId, String scope, String name, boolean system, Set<String> permissions, long version) {

        static RoleResponse of(Role role) {
            return new RoleResponse(
                    role.id().value(), role.tenantId().value(), role.scope().name(), role.name(), role.isSystem(),
                    role.permissions(), role.version());
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
    @RequiresPermission("platform:role:manage")
    RoleResponse create(@RequestBody CreateRequest request) {
        Actor actor = currentActor.get();
        return RoleResponse.of(createRole.execute(actor, new CreateRoleUseCase.Command(
                actor.tenantId(), actor.scope(), request.name(), request.permissions())));
    }

    @GetMapping
    @RequiresPermission("platform:role:read")
    List<RoleResponse> list(@RequestParam UUID tenantId) {
        return queries.list(tenantId).stream().map(view -> new RoleResponse(
                view.id(), view.tenantId(), view.scope(), view.name(), view.system(), view.permissions(), view.version())).toList();
    }

    @PutMapping("/{id}/permissions")
    @RequiresPermission("platform:role:manage")
    ResponseEntity<RoleResponse> replacePermissions(
            @PathVariable UUID id, @RequestBody PermissionsRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Role role = manageRole.execute(currentActor.get(), new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.REPLACE_PERMISSIONS, request.permissions(), null, null,
                ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(role.version())).body(RoleResponse.of(role));
    }

    @PutMapping("/{id}")
    @RequiresPermission("platform:role:manage")
    ResponseEntity<RoleResponse> rename(
            @PathVariable UUID id, @RequestBody RenameRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Role role = manageRole.execute(currentActor.get(), new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.RENAME, Set.of(), request.name(), null,
                ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(role.version())).body(RoleResponse.of(role));
    }

    @PostMapping("/{id}/users/{userId}")
    @RequiresPermission("platform:role:manage")
    RoleResponse assign(@PathVariable UUID id, @PathVariable UUID userId) {
        return RoleResponse.of(manageRole.execute(currentActor.get(), new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, new UserId(userId), ExpectedVersion.any())));
    }

    @DeleteMapping("/{id}/users/{userId}")
    @RequiresPermission("platform:role:manage")
    RoleResponse unassign(@PathVariable UUID id, @PathVariable UUID userId) {
        return RoleResponse.of(manageRole.execute(currentActor.get(), new ManageRoleUseCase.Command(
                new RoleId(id), ManageRoleUseCase.Action.UNASSIGN, Set.of(), null, new UserId(userId), ExpectedVersion.any())));
    }
}
