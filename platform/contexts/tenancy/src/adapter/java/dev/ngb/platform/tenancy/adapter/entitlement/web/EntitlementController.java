package dev.ngb.platform.tenancy.adapter.entitlement.web;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.entitlement.command.DisableAppUseCase;
import dev.ngb.platform.tenancy.application.entitlement.command.EnableAppUseCase;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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

    private final EnableAppUseCase enableApp;
    private final DisableAppUseCase disableApp;
    private final CurrentActor currentActor;

    EntitlementController(EnableAppUseCase enableApp, DisableAppUseCase disableApp, CurrentActor currentActor) {
        this.enableApp = enableApp;
        this.disableApp = disableApp;
        this.currentActor = currentActor;
    }

    @PutMapping("/{appCode}")
    @RequiresPermission("platform:entitlement:manage")
    ResponseEntity<EntitlementResponse> set(
            @PathVariable UUID tenantId, @PathVariable String appCode, @RequestBody EntitlementRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        TenantId tenant = new TenantId(tenantId);
        String actor = currentActor.get().auditName();
        ExpectedVersion expected = ExpectedVersion.fromIfMatch(ifMatch);
        AppEntitlement entitlement = request.enabled()
                ? enableApp.execute(new EnableAppUseCase.Command(tenant, appCode, actor, requestId, expected))
                : disableApp.execute(new DisableAppUseCase.Command(tenant, appCode, actor, requestId, expected));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(entitlement.version())).body(EntitlementResponse.of(entitlement));
    }
}
