package dev.ngb.platform.tenancy.adapter.uiremote.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.platform.tenancy.application.uiremote.command.RegisterUiRemoteVersionUseCase;
import dev.ngb.platform.tenancy.application.uiremote.command.SetUiRemoteStateUseCase;
import dev.ngb.platform.tenancy.application.uiremote.exception.UnknownUiRemoteException;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteListing;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteQueries;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteVersionView;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteView;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemote;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's UI remote registry as Platform Administration drives it: CI registers a version, an operator enables,
 * disables and points a remote at one of its versions.
 */
@Tag(
        name = "UI remotes",
        description =
                "The console's UI remote registry: CI registers versions; an operator enables, disables and activates "
                + "them.")
@RestController
@RequestMapping("/api/v1/ui-remotes")
@Callers(Caller.PLATFORM_USER)
class UiRemoteController {

    record RegisterRequest(
            String version, String entryPath, String integrity, Integer sdkMajor, String appCode, String title,
            List<String> audiences, Integer navOrder) {}

    record StateRequest(Boolean enabled, String activeVersion, String title, Integer navOrder) {}

    record VersionResponse(
            String version, String entryPath, String integrity, int sdkMajor, String registeredAt, String registeredBy) {

        static VersionResponse of(UiRemoteVersionView view) {
            return new VersionResponse(
                    view.version(), view.entryPath(), view.integrity(), view.sdkMajor(), view.registeredAt(),
                    view.registeredBy());
        }
    }

    record RemoteResponse(
            String name, String appCode, String title, int navOrder, List<String> audiences, boolean enabled,
            String activeVersion, String createdAt, long version, List<VersionResponse> versions) {

        static RemoteResponse of(UiRemoteView view) {
            return new RemoteResponse(
                    view.name(), view.appCode(), view.title(), view.navOrder(), view.audiences(), view.enabled(),
                    view.activeVersion(), view.createdAt(), view.version(),
                    view.versions().stream().map(VersionResponse::of).toList());
        }
    }

    private final RegisterUiRemoteVersionUseCase register;
    private final SetUiRemoteStateUseCase setState;
    private final UiRemoteQueries queries;
    private final CurrentActor currentActor;

    UiRemoteController(
            RegisterUiRemoteVersionUseCase register, SetUiRemoteStateUseCase setState, UiRemoteQueries queries,
            CurrentActor currentActor) {
        this.register = register;
        this.setState = setState;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link UiRemoteListing}. */
    @Operation(
            summary = "List UI remotes",
            description =
                    "Pages through the console's UI remotes, with the list-query contract's filtering, sorting and "
                    + "paging.")
    @GetMapping
    @Listing(UiRemoteListing.class)
    @RequiresPermission(PlatformPermissions.UI_REMOTE_READ)
    Page<RemoteResponse> listRemotes(ListQuery query) {
        return queries.list(query).map(RemoteResponse::of);
    }

    /**
     * CI registers a version; the first call also creates the remote, disabled and with no active version. A repeated
     * call with the same content answers 200 instead of 201, and never activates anything.
     */
    @Callers(Caller.INTERNAL)
    @Operation(
            summary = "Register a UI remote version",
            description =
                    "Called by CI after it publishes a build. The first call also creates the remote, disabled and with "
                    + "no active version; registering never enables a remote or changes its active version, so a deploy "
                    + "cannot reach a browser until an operator activates it. Repeating the same version with the same "
                    + "content answers `200` instead of `201`; the same version with different content is a conflict, "
                    + "because a version is immutable once a shell may have verified it. Requires "
                    + "`platform:ui-remote:register`.")
    @PostMapping("/{name}/versions")
    @RequiresPermission(PlatformPermissions.UI_REMOTE_REGISTER)
    ResponseEntity<VersionResponse> registerVersion(
            @PathVariable String name, @RequestBody RegisterRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        if (request.version() == null || request.entryPath() == null || request.integrity() == null
                || request.sdkMajor() == null) {
            throw new IllegalArgumentException("version, entryPath, integrity and sdkMajor are required");
        }
        RegisterUiRemoteVersionUseCase.Result result = register.execute(new RegisterUiRemoteVersionUseCase.Command(
                name, request.version(), request.entryPath(), request.integrity(), request.sdkMajor(), request.appCode(),
                request.title(), request.audiences(), request.navOrder(), currentActor.get().auditName(), requestId));
        VersionResponse body = VersionResponse.of(new UiRemoteVersionView(
                result.version().version(), result.version().entryPath(), result.version().integrity(),
                result.version().sdkMajor(), result.version().registeredAt().toString(), result.version().registeredBy()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    /** The operator's one write: enable, disable, activate a version, retitle or reorder. */
    @Operation(
            summary = "Change a UI remote",
            description =
                    "The operator's one write on a remote: enable or disable it, point it at a registered version with "
                    + "`activeVersion` (a rollback is the same call with an older version), and edit its `title` and "
                    + "`navOrder`. `enabled` is required. Pass the remote's `ETag` in `If-Match`; a stale version is "
                    + "refused.")
    @PutMapping("/{name}")
    @RequiresPermission(PlatformPermissions.UI_REMOTE_MANAGE)
    ResponseEntity<RemoteResponse> setState(
            @PathVariable String name, @RequestBody StateRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        UiRemote remote = setState.execute(new SetUiRemoteStateUseCase.Command(
                name, request.enabled(), request.activeVersion(), request.title(), request.navOrder(),
                currentActor.get().auditName(), requestId, ExpectedVersion.fromIfMatch(ifMatch)));
        RemoteResponse body = queries.byName(remote.name()).map(RemoteResponse::of)
                .orElseThrow(() -> new UnknownUiRemoteException(remote.name()));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(remote.version())).body(body);
    }
}
