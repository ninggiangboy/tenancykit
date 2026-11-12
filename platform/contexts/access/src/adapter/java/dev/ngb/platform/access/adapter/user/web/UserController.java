package dev.ngb.platform.access.adapter.user.web;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserPermissionsUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserUseCase;
import dev.ngb.platform.access.application.user.query.UserQueries;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration of users and their direct permissions. */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record CreateRequest(UUID tenantId, String tenantCode, String scope, String email, String displayName, String temporaryPassword) {}

    record UpdateRequest(String action, String name) {}

    record UserResponse(UUID id, UUID tenantId, String scope, String email, String displayName, String status, long version) {

        static UserResponse of(User user) {
            return new UserResponse(
                    user.id().value(), user.tenantId().value(), user.scope().name(), user.email(), user.displayName(),
                    user.status().name(), user.version());
        }
    }

    private final CreateUserUseCase createUser;
    private final ManageUserUseCase manageUser;
    private final ManageUserPermissionsUseCase managePermissions;
    private final UserQueries queries;

    UserController(
            CreateUserUseCase createUser, ManageUserUseCase manageUser, ManageUserPermissionsUseCase managePermissions, UserQueries queries) {
        this.createUser = createUser;
        this.manageUser = manageUser;
        this.managePermissions = managePermissions;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("platform:user:manage")
    UserResponse create(@RequestBody CreateRequest request) {
        return UserResponse.of(createUser.execute(new CreateUserUseCase.Command(
                request.tenantCode(), new TenantId(request.tenantId()), PermissionScope.valueOf(request.scope()),
                request.email(), request.displayName(), request.temporaryPassword())));
    }

    @GetMapping
    @RequiresPermission("platform:user:read")
    List<UserResponse> list(@RequestParam UUID tenantId) {
        return queries.list(tenantId).stream().map(view -> new UserResponse(
                view.id(), view.tenantId(), view.scope(), view.email(), view.displayName(), view.status(), view.version())).toList();
    }

    @PatchMapping("/{id}")
    @RequiresPermission("platform:user:manage")
    UserResponse update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        return UserResponse.of(manageUser.execute(new ManageUserUseCase.Command(
                new UserId(id), ManageUserUseCase.Action.valueOf(request.action()), request.name())));
    }

    @PutMapping("/{id}/permissions/{code}")
    @RequiresPermission("platform:user:manage")
    UserResponse setPermission(@PathVariable UUID id, @PathVariable String code, @RequestParam boolean grant) {
        return UserResponse.of(managePermissions.execute(new ManageUserPermissionsUseCase.Command(new UserId(id), code, grant)));
    }
}
