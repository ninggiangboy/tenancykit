package dev.ngb.platform.tenancy.adapter.tenant.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.tenant.command.AddTenantDomainUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.DeactivateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.RenameTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.query.TenantListing;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration of tenants. */
@Tag(
        name = "Tenants",
        description =
                "Platform administration of tenants: list, rename, deactivate and map hostnames.")
@RestController
@RequestMapping("/api/v1/tenants")
@Callers(Caller.PLATFORM_USER)
class TenantController {

    record RenameRequest(String name) {}

    record DomainRequest(String hostname) {}

    record TenantResponse(UUID id, String code, String name, String status, String createdAt, long version) {

        static TenantResponse of(Tenant tenant) {
            return new TenantResponse(
                    tenant.id().value(), tenant.code().value(), tenant.name(), tenant.status().name(),
                    tenant.createdAt().toString(), tenant.version());
        }

        static TenantResponse ofView(TenantView view) {
            return new TenantResponse(
                    view.id(), view.code(), view.name(), view.status(), view.createdAt().toString(), view.version());
        }
    }

    private final RenameTenantUseCase renameTenant;
    private final DeactivateTenantUseCase deactivateTenant;
    private final AddTenantDomainUseCase addDomain;
    private final TenantQueries queries;
    private final CurrentActor currentActor;

    TenantController(
            RenameTenantUseCase renameTenant, DeactivateTenantUseCase deactivateTenant,
            AddTenantDomainUseCase addDomain, TenantQueries queries, CurrentActor currentActor) {
        this.renameTenant = renameTenant;
        this.deactivateTenant = deactivateTenant;
        this.addDomain = addDomain;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link TenantListing}. */
    @Operation(
            summary = "List tenants",
            description =
                    "Pages through the suite's Tenants, with the list-query contract's filtering, sorting and paging. ")
    @GetMapping
    @Listing(TenantListing.class)
    @RequiresPermission(PlatformPermissions.TENANT_READ)
    Page<TenantResponse> list(ListQuery query) {
        return queries.list(query).map(TenantResponse::ofView);
    }

    @Operation(
            summary = "Rename a tenant",
            description =
                    "Changes the Tenant's display name; its code does not change. The version is bumped and the new state "
                    + "is published. Pass the tenant's `ETag` in `If-Match`; a stale version is refused. Requires "
                    + "`platform:tenant:rename`.")
    @PutMapping("/{id}")
    @RequiresPermission(PlatformPermissions.TENANT_RENAME)
    ResponseEntity<TenantResponse> rename(
            @PathVariable UUID id, @RequestBody RenameRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Tenant tenant = renameTenant.execute(new RenameTenantUseCase.Command(
                new TenantId(id), request.name(), currentActor.get().auditName(), requestId, ExpectedVersion.fromIfMatch(ifMatch)));
        return conditional(tenant, TenantResponse.of(tenant));
    }

    @Operation(
            summary = "Deactivate a tenant",
            description =
                    "Deactivates the Tenant. Deactivating a Tenant that is already inactive changes nothing and publishes "
                    + "nothing. Pass the tenant's `ETag` in `If-Match`; a stale version is refused. Requires "
                    + "`platform:tenant:deactivate`.")
    @PostMapping("/{id}/deactivate")
    @RequiresPermission(PlatformPermissions.TENANT_DEACTIVATE)
    ResponseEntity<TenantResponse> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Tenant tenant = deactivateTenant.execute(new DeactivateTenantUseCase.Command(
                new TenantId(id), currentActor.get().auditName(), requestId, ExpectedVersion.fromIfMatch(ifMatch)));
        return conditional(tenant, TenantResponse.of(tenant));
    }

    @Operation(
            summary = "Add a hostname to a tenant",
            description =
                    "Maps one more hostname to the Tenant, so its people reach the console on that host. The host also "
                    + "becomes a sign-in redirect target; if that registration fails the request still succeeds and a "
                    + "reconciler repairs it. Answers `201` with no body.")
    @PostMapping("/{id}/domains")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.TENANT_DOMAIN_MANAGE)
    void addDomain(
            @PathVariable UUID id, @RequestBody DomainRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        addDomain.execute(new AddTenantDomainUseCase.Command(
                new TenantId(id), request.hostname(), currentActor.get().auditName(), requestId));
    }

    private static ResponseEntity<TenantResponse> conditional(Tenant tenant, TenantResponse body) {
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(tenant.version())).body(body);
    }
}
