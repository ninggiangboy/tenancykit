package dev.ngb.platform.tenancy.adapter.entitlement.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.entitlement.query.AppEntitlementView;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementQueries;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration of app entitlements. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/apps")
class EntitlementController {

    record EntitlementRequest(Boolean enabled) {}

    record EntitlementResponse(UUID tenantId, String appCode, boolean enabled, long version) {

        static EntitlementResponse of(AppEntitlement entitlement) {
            return new EntitlementResponse(
                    entitlement.tenantId().value(), entitlement.appCode(), entitlement.isEnabled(), entitlement.version());
        }
    }

    record AppResponse(String appCode, boolean enabled, long version) {

        static AppResponse of(AppEntitlementView view) {
            return new AppResponse(view.appCode(), view.enabled(), view.version());
        }
    }

    private final SetAppEntitlementUseCase setApp;
    private final EntitlementQueries queries;
    private final CurrentActor currentActor;

    EntitlementController(SetAppEntitlementUseCase setApp, EntitlementQueries queries, CurrentActor currentActor) {
        this.setApp = setApp;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Every app of the suite with this Tenant's state of it; an app the tenant never changed is disabled at 0. */
    @GetMapping
    @RequiresPermission(PlatformPermissions.TENANT_READ)
    List<AppResponse> apps(@PathVariable UUID tenantId) {
        return queries.appsForTenant(new TenantId(tenantId)).stream().map(AppResponse::of).toList();
    }

    @PutMapping("/{appCode}")
    @RequiresPermission(PlatformPermissions.ENTITLEMENT_MANAGE)
    ResponseEntity<EntitlementResponse> set(
            @PathVariable UUID tenantId, @PathVariable String appCode, @RequestBody EntitlementRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        TenantId tenant = new TenantId(tenantId);
        String actor = currentActor.get().auditName();
        ExpectedVersion expected = ExpectedVersion.fromIfMatch(ifMatch);
        AppEntitlement entitlement = setApp.execute(
                new SetAppEntitlementUseCase.Command(tenant, appCode, request.enabled(), actor, requestId, expected));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(entitlement.version())).body(EntitlementResponse.of(entitlement));
    }
}
