package dev.ngb.platform.tenancy.adapter.tenant.web;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.tenant.command.AddTenantDomainUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.DeactivateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.RenameTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
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
@RestController
@RequestMapping("/api/v1/tenants")
class TenantController {

    record RenameRequest(String name) {}

    record DomainRequest(String hostname) {}

    record TenantResponse(UUID id, String code, String name, String status, String createdAt, long version) {

        static TenantResponse of(Tenant tenant) {
            return new TenantResponse(
                    tenant.id().value(), tenant.code().value(), tenant.name(), tenant.status().name(),
                    tenant.createdAt().toString(), tenant.version());
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

    @GetMapping
    @RequiresPermission("platform:tenant:read")
    List<TenantResponse> list() {
        return queries.list().stream().map(view -> new TenantResponse(
                view.id(), view.code(), view.name(), view.status(), view.createdAt().toString(), view.version())).toList();
    }

    @PutMapping("/{id}")
    @RequiresPermission("platform:tenant:rename")
    ResponseEntity<TenantResponse> rename(
            @PathVariable UUID id, @RequestBody RenameRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Tenant tenant = renameTenant.execute(new RenameTenantUseCase.Command(
                new TenantId(id), request.name(), currentActor.get().auditName(), requestId, ExpectedVersion.fromIfMatch(ifMatch)));
        return conditional(tenant, TenantResponse.of(tenant));
    }

    @PostMapping("/{id}/deactivate")
    @RequiresPermission("platform:tenant:deactivate")
    ResponseEntity<TenantResponse> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Tenant tenant = deactivateTenant.execute(new DeactivateTenantUseCase.Command(
                new TenantId(id), currentActor.get().auditName(), requestId, ExpectedVersion.fromIfMatch(ifMatch)));
        return conditional(tenant, TenantResponse.of(tenant));
    }

    @PostMapping("/{id}/domains")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("platform:tenant-domain:manage")
    void addDomain(
            @PathVariable UUID id, @RequestBody DomainRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        addDomain.execute(new AddTenantDomainUseCase.Command(
                new TenantId(id), request.hostname(), currentActor.get().auditName(), requestId));
    }

    private static ResponseEntity<TenantResponse> conditional(Tenant tenant, TenantResponse body) {
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(tenant.version())).body(body);
    }
}
