package dev.ngb.platform.boot.config;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.adapter.entitlement.persistence.JooqEntitlementReadRepository;
import dev.ngb.platform.tenancy.adapter.entitlement.persistence.JooqEntitlementRepository;
import dev.ngb.platform.tenancy.adapter.tenant.persistence.JooqTenantDomainRepository;
import dev.ngb.platform.tenancy.adapter.tenant.persistence.JooqTenantReadRepository;
import dev.ngb.platform.tenancy.adapter.tenant.persistence.JooqTenantRepository;
import dev.ngb.platform.tenancy.adapter.uiremote.persistence.JooqUiRemoteReadRepository;
import dev.ngb.platform.tenancy.adapter.uiremote.persistence.JooqUiRemoteRepository;
import dev.ngb.platform.tenancy.api.provisioning.TenantProvisioning;
import dev.ngb.platform.tenancy.application.service.TenantProvisioningService;
import dev.ngb.platform.boot.catalog.ClasspathAppCatalog;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.entitlement.port.AppCatalog;
import dev.ngb.platform.tenancy.application.entitlement.port.AppEntitlementRepository;
import dev.ngb.platform.tenancy.application.entitlement.query.AvailableApps;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementQueries;
import dev.ngb.platform.tenancy.application.entitlement.query.EntitlementReadRepository;
import dev.ngb.platform.tenancy.application.tenant.command.AddTenantDomainUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.DeactivateTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.EnsurePlatformDomainUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.RenameTenantUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.ResolveHostUseCase;
import dev.ngb.platform.tenancy.application.tenant.port.ConsoleHostPort;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import dev.ngb.platform.access.application.auth.port.ConsoleClientPort;
import dev.ngb.platform.tenancy.application.uiremote.command.RegisterUiRemoteVersionUseCase;
import dev.ngb.platform.tenancy.application.uiremote.command.SetUiRemoteStateUseCase;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteReadRepository;
import dev.ngb.platform.tenancy.application.uiremote.port.UiRemoteRepository;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteQueries;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteQueries;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the tenancy context. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class TenancyConfiguration {

    @Bean
    TenantRepository tenantRepository(DSLContext dsl) {
        return new JooqTenantRepository(dsl);
    }

    @Bean
    TenantDomainRepository tenantDomainRepository(DSLContext dsl) {
        return new JooqTenantDomainRepository(dsl);
    }

    @Bean
    TenantReadRepository tenantReadRepository(DSLContext dsl) {
        return new JooqTenantReadRepository(dsl);
    }

    @Bean
    AppEntitlementRepository appEntitlementRepository(DSLContext dsl) {
        return new JooqEntitlementRepository(dsl);
    }

    @Bean
    EntitlementReadRepository entitlementReadRepository(DSLContext dsl) {
        return new JooqEntitlementReadRepository(dsl);
    }

    @Bean
    ClasspathAppCatalog appCatalog() {
        return new ClasspathAppCatalog();
    }

    @Bean
    TenantProvisioning tenantProvisioning(CreateTenantUseCase createTenant) {
        return new TenantProvisioningService(createTenant);
    }

    @Bean
    CreateTenantUseCase createTenantUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, TenantDomainRepository domains, ConsoleHostPort consoleHosts,
            EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock,
            @Value("${platform.tenancy.base-domain:localhost}") String baseDomain) {
        return new CreateTenantUseCase(unitOfWork, tenants, domains, consoleHosts, events, auditLog, ids, clock, baseDomain);
    }

    @Bean
    RenameTenantUseCase renameTenantUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new RenameTenantUseCase(unitOfWork, tenants, events, auditLog, ids, clock);
    }

    @Bean
    DeactivateTenantUseCase deactivateTenantUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new DeactivateTenantUseCase(unitOfWork, tenants, events, auditLog, ids, clock);
    }

    @Bean
    ConsoleHostPort consoleHostPort(ConsoleClientPort consoleClient) {
        return consoleClient::registerHost;
    }

    @Bean
    AddTenantDomainUseCase addTenantDomainUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, TenantDomainRepository domains, ConsoleHostPort consoleHosts,
            EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new AddTenantDomainUseCase(unitOfWork, tenants, domains, consoleHosts, events, auditLog, ids, clock);
    }

    @Bean
    EnsurePlatformDomainUseCase ensurePlatformDomainUseCase(
            UnitOfWork unitOfWork, TenantDomainRepository domains, EventPublisher events, IdGenerator ids, Clock clock) {
        return new EnsurePlatformDomainUseCase(unitOfWork, domains, events, ids, clock);
    }

    @Bean
    ResolveHostUseCase resolveHostUseCase(UnitOfWork unitOfWork, TenantReadRepository tenants) {
        return new ResolveHostUseCase(unitOfWork, tenants);
    }

    @Bean
    TenantQueries tenantQueries(UnitOfWork unitOfWork, TenantReadRepository tenants) {
        return new TenantQueries(unitOfWork, tenants);
    }

    @Bean
    EntitlementQueries entitlementQueries(
            UnitOfWork unitOfWork, EntitlementReadRepository entitlements, TenantReadRepository tenants, AvailableApps apps) {
        return new EntitlementQueries(unitOfWork, entitlements, tenants, apps);
    }

    @Bean
    UiRemoteRepository uiRemoteRepository(DSLContext dsl) {
        return new JooqUiRemoteRepository(dsl);
    }

    @Bean
    UiRemoteReadRepository uiRemoteReadRepository(DSLContext dsl) {
        return new JooqUiRemoteReadRepository(dsl);
    }

    @Bean
    RegisterUiRemoteVersionUseCase registerUiRemoteVersionUseCase(
            UnitOfWork unitOfWork, UiRemoteRepository remotes, AuditLog auditLog, Clock clock) {
        return new RegisterUiRemoteVersionUseCase(unitOfWork, remotes, auditLog, clock);
    }

    @Bean
    SetUiRemoteStateUseCase setUiRemoteStateUseCase(
            UnitOfWork unitOfWork, UiRemoteRepository remotes, AuditLog auditLog, Clock clock) {
        return new SetUiRemoteStateUseCase(unitOfWork, remotes, auditLog, clock);
    }

    @Bean
    UiRemoteQueries uiRemoteQueries(UnitOfWork unitOfWork, UiRemoteReadRepository remotes) {
        return new UiRemoteQueries(unitOfWork, remotes);
    }

    @Bean
    ConsoleRemoteQueries consoleRemoteQueries(UnitOfWork unitOfWork, UiRemoteReadRepository remotes) {
        return new ConsoleRemoteQueries(unitOfWork, remotes);
    }

    @Bean
    SetAppEntitlementUseCase setAppEntitlementUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, AppEntitlementRepository entitlements, AppCatalog apps,
            EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new SetAppEntitlementUseCase(unitOfWork, tenants, entitlements, apps, events, auditLog, ids, clock);
    }
}
