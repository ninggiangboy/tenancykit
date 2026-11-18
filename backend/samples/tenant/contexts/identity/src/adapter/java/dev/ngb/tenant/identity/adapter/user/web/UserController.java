package dev.ngb.tenant.identity.adapter.user.web;

import dev.ngb.tenant.identity.api.security.AuthenticatedUser;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.api.security.TenantPermissions;
import dev.ngb.tenant.identity.application.user.command.ManageUsersUseCase;
import dev.ngb.tenant.identity.application.user.command.RegisterUserUseCase;
import dev.ngb.tenant.identity.application.user.query.UserReadRepository;
import dev.ngb.tenant.identity.domain.user.User;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.RequiresPermission;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account administration for business roles and permissions. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record CreateUserRequest(String email, String displayName, String password, Role role) {}

    public record ChangeRoleRequest(Role role) {}

    private final ManageUsersUseCase manage;
    private final RegisterUserUseCase register;
    private final UserReadRepository users;

    public UserController(ManageUsersUseCase manage, RegisterUserUseCase register, UserReadRepository users) {
        this.manage = manage;
        this.register = register;
        this.users = users;
    }

    @GetMapping("/me")
    @Authenticated
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return UserResponse.of(users.byId(principal.id()).orElseThrow(() -> new NotFoundException("user not found")));
    }

    @GetMapping
    @RequiresPermission(TenantPermissions.USER_MANAGE)
    public List<UserResponse> list() {
        return users.list().stream().map(UserResponse::of).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(TenantPermissions.USER_MANAGE)
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.of(users.byId(id).orElseThrow(() -> new NotFoundException("user not found")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(TenantPermissions.USER_MANAGE)
    public UserResponse create(@RequestBody CreateUserRequest request) {
        User user = register.execute(new RegisterUserUseCase.Command(
                request.email(), request.displayName(), request.password(), request.role()));
        return UserResponse.of(user);
    }

    @PutMapping("/{id}/role")
    @RequiresPermission(TenantPermissions.USER_MANAGE)
    public UserResponse changeRole(@PathVariable UUID id, @RequestBody ChangeRoleRequest request) {
        return UserResponse.of(manage.changeRole(id, request.role()));
    }

    @PostMapping("/{id}/deactivate")
    @RequiresPermission(TenantPermissions.USER_MANAGE)
    public UserResponse deactivate(@PathVariable UUID id) {
        return UserResponse.of(manage.deactivate(id));
    }
}
