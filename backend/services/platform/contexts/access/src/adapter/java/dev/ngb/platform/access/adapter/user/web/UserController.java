package dev.ngb.platform.access.adapter.user.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserPermissionsUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserUseCase;
import dev.ngb.platform.access.application.user.query.UserListing;
import dev.ngb.platform.access.api.user.UserQueries;
import dev.ngb.platform.access.api.user.UserView;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration of users and their direct permissions. */
@Tag(
        name = "Users",
        description =
                "Platform administration of users: create, enable, disable, rename and grant direct permissions.")
@RestController
@RequestMapping("/api/v1/users")
@Callers({Caller.PLATFORM_USER, Caller.TENANT_USER})
class UserController {

    record CreateRequest(String tenantCode, String email, String displayName, String temporaryPassword) {}

    record UpdateRequest(String action, String name) {}

    record UserResponse(UUID id, UUID tenantId, String scope, String email, String displayName, String status, long version) {

        static UserResponse of(User user) {
            return new UserResponse(
                    user.id().value(), user.tenantId().value(), user.scope().name(), user.email(), user.displayName(),
                    user.status().name(), user.version());
        }

        static UserResponse ofView(UserView view) {
            return new UserResponse(
                    view.id(), view.tenantId(), view.scope(), view.email(), view.displayName(), view.status(), view.version());
        }
    }

    private final CreateUserUseCase createUser;
    private final ManageUserUseCase manageUser;
    private final ManageUserPermissionsUseCase managePermissions;
    private final UserQueries queries;
    private final CurrentActor currentActor;

    UserController(
            CreateUserUseCase createUser, ManageUserUseCase manageUser, ManageUserPermissionsUseCase managePermissions, UserQueries queries,
            CurrentActor currentActor) {
        this.createUser = createUser;
        this.manageUser = manageUser;
        this.managePermissions = managePermissions;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Create a user",
            description =
                    "Creates the user's sign-in identity and the Platform user for `tenantCode`, with a temporary "
                    + "password. A Tenant-scope caller cannot create a Platform-scope user. Answers `201` with the user. ")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.USER_MANAGE)
    UserResponse create(
            @RequestBody CreateRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Actor actor = currentActor.get();
        return UserResponse.of(createUser.execute(actor, requestId, new CreateUserUseCase.Command(
                request.tenantCode(), actor.tenantId(), actor.scope(),
                request.email(), request.displayName(), request.temporaryPassword())));
    }

    /**
     * Filter, sort and paging follow the list-query contract; the documented parameters come from {@link UserListing}.
     * {@code tenant_id} is a filter rather than a parameter, and a Tenant-scope caller never sees another Tenant.
     */
    @Operation(
            summary = "List users",
            description =
                    "Pages through users, with the list-query contract's filtering, sorting and paging. `tenant_id` is a "
                    + "filter, and a Tenant-scope caller never sees another Tenant's users.")
    @GetMapping
    @Listing(UserListing.class)
    @RequiresPermission(PlatformPermissions.USER_READ)
    Page<UserResponse> list(ListQuery query) {
        return queries.list(query, tenantScope()).map(UserResponse::ofView);
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

    @Operation(
            summary = "Enable, disable or rename a user",
            description =
                    "Applies one `action` to the user: `ENABLE`, `DISABLE`, or `RENAME` with the new `name`. Every "
                    + "effective change publishes the user's new access and is audited. Pass the user's `ETag` in "
                    + "`If-Match`; a stale version is refused.")
    @PatchMapping("/{id}")
    @RequiresPermission(PlatformPermissions.USER_MANAGE)
    ResponseEntity<UserResponse> update(
            @PathVariable UUID id, @RequestBody UpdateRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        User user = manageUser.execute(currentActor.get(), requestId, new ManageUserUseCase.Command(
                new UserId(id), ManageUserUseCase.Action.valueOf(request.action()), request.name(),
                ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(user.version())).body(UserResponse.of(user));
    }

    @Operation(
            summary = "Grant or revoke a direct permission",
            description =
                    "Grants (`grant=true`) or revokes (`grant=false`) one permission `code` directly on the user, outside "
                    + "any role. Each effective change is audited. Pass the user's `ETag` in `If-Match`; a stale version is "
                    + "refused.")
    @PutMapping("/{id}/permissions/{code}")
    @RequiresPermission(PlatformPermissions.USER_MANAGE)
    ResponseEntity<UserResponse> setPermission(
            @PathVariable UUID id, @PathVariable String code, @RequestParam boolean grant,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        User user = managePermissions.execute(currentActor.get(), requestId, new ManageUserPermissionsUseCase.Command(
                new UserId(id), code, grant, ExpectedVersion.fromIfMatch(ifMatch)));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(user.version())).body(UserResponse.of(user));
    }
}
