package dev.ngb.platform.access.adapter.tenant.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.access.application.tenant.command.ProvisionTenantUseCase;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Provisioning a Tenant with its first administrator, in one call. */
@RestController
class ProvisionTenantController {

    record ProvisionRequest(String code, String name, String adminEmail, String adminTemporaryPassword) {}

    record ProvisionedResponse(UUID tenantId, String adminEmail) {}

    private final ProvisionTenantUseCase provision;

    ProvisionTenantController(ProvisionTenantUseCase provision) {
        this.provision = provision;
    }

    @PostMapping("/api/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("platform:tenant:create")
    ProvisionedResponse create(@RequestBody ProvisionRequest request) {
        var provisioned = provision.execute(new ProvisionTenantUseCase.Command(
                request.code(), request.name(), request.adminEmail(), request.adminTemporaryPassword()));
        return new ProvisionedResponse(provisioned.tenantId().value(), provisioned.adminEmail());
    }
}
