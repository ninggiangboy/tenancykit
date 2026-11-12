package dev.ngb.platform.boot.startup;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.boot.config.PlatformProperties;
import dev.ngb.platform.tenancy.application.tenant.command.EnsurePlatformDomainUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Makes the Platform's own hostname resolve, idempotently, on every start. */
@Component
class PlatformBootstrap implements ApplicationRunner {

    private final EnsurePlatformDomainUseCase ensurePlatformDomain;
    private final TenantContext tenantContext;
    private final PlatformProperties properties;

    PlatformBootstrap(EnsurePlatformDomainUseCase ensurePlatformDomain, TenantContext tenantContext, PlatformProperties properties) {
        this.ensurePlatformDomain = ensurePlatformDomain;
        this.tenantContext = tenantContext;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hostname = properties.tenancy() == null || properties.tenancy().platformHostname() == null
                ? "platform.localhost"
                : properties.tenancy().platformHostname();
        tenantContext.runAs(RequestScope.platform(), () -> ensurePlatformDomain.execute(hostname));
    }
}
