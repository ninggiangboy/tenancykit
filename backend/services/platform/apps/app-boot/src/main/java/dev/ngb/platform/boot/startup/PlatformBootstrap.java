package dev.ngb.platform.boot.startup;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.boot.config.PlatformProperties;
import dev.ngb.platform.access.application.user.command.BootstrapPlatformAdminUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.EnsurePlatformDomainUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Makes the Platform's own hostname resolve and creates the configured first administrator, both idempotently. */
@Component
class PlatformBootstrap implements ApplicationRunner {

    private final EnsurePlatformDomainUseCase ensurePlatformDomain;
    private final BootstrapPlatformAdminUseCase bootstrapAdmin;
    private final TenantContext tenantContext;
    private final PlatformProperties properties;

    PlatformBootstrap(
            EnsurePlatformDomainUseCase ensurePlatformDomain, BootstrapPlatformAdminUseCase bootstrapAdmin, TenantContext tenantContext,
            PlatformProperties properties) {
        this.ensurePlatformDomain = ensurePlatformDomain;
        this.bootstrapAdmin = bootstrapAdmin;
        this.tenantContext = tenantContext;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hostname = properties.tenancy() == null || properties.tenancy().platformHostname() == null
                ? "platform.localhost"
                : properties.tenancy().platformHostname();
        tenantContext.runAs(RequestScope.platform(), () -> ensurePlatformDomain.execute(hostname));
        if (properties.bootstrap() != null) {
            tenantContext.runAs(
                    RequestScope.platform(),
                    () -> bootstrapAdmin.execute(
                            properties.bootstrap().platformAdminEmail(), properties.bootstrap().platformAdminTemporaryPassword()));
        }
    }
}
