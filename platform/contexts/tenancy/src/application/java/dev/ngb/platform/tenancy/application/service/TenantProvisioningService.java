package dev.ngb.platform.tenancy.application.service;
import dev.ngb.platform.tenancy.api.provisioning.TenantProvisioning;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import java.util.UUID;

/** Serves the tenancy api from the same use case the tenant controller uses. */
public class TenantProvisioningService implements TenantProvisioning {

    private final CreateTenantUseCase createTenant;

    public TenantProvisioningService(CreateTenantUseCase createTenant) {
        this.createTenant = createTenant;
    }

    @Override
    public UUID createTenant(String code, String name) {
        return createTenant.execute(new CreateTenantUseCase.Command(code, name, "service/platform", "provision")).id().value();
    }
}
