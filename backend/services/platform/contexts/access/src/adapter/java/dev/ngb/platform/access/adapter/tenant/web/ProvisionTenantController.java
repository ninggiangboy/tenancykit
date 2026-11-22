package dev.ngb.platform.access.adapter.tenant.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.platform.access.application.tenant.command.ProvisionTenantUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provisioning a Tenant with its first administrator, in one call. */
@Tag(
        name = "Tenant provisioning",
        description =
                "Creates a tenant together with its Tenant Administrator role and first administrator in one call.")
@RestController
@Callers(Caller.PLATFORM_USER)
class ProvisionTenantController {

    record ProvisionRequest(String code, String name, String adminEmail, String adminTemporaryPassword) {}

    record ProvisionedResponse(UUID tenantId, String adminEmail) {}

    private final ProvisionTenantUseCase provision;

    ProvisionTenantController(ProvisionTenantUseCase provision) {
        this.provision = provision;
    }

    @Operation(
            summary = "Provision a tenant with its first administrator",
            description =
                    "Creates a Tenant, seeds its Tenant Administrator role with every tenant-scope permission and, when "
                    + "`adminEmail` is given, creates the first administrator with `adminTemporaryPassword` and assigns "
                    + "that role, all in one call. A failure leaves neither a Tenant without its role nor an administrator "
                    + "without the Tenant. Answers `201` with the tenant id and the administrator's email. Requires "
                    + "`platform:tenant:create`.")
    @PostMapping("/api/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.TENANT_CREATE)
    ProvisionedResponse create(@RequestBody ProvisionRequest request) {
        var provisioned = provision.execute(new ProvisionTenantUseCase.Command(
                request.code(), request.name(), request.adminEmail(), request.adminTemporaryPassword()));
        return new ProvisionedResponse(provisioned.tenantId().value(), provisioned.adminEmail());
    }
}
