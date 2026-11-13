package dev.ngb.platform.boot.config;

import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.boot.catalog.CatalogTenantSystemScopes;
import dev.ngb.platform.boot.catalog.TenantReadCodes;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.integration.adapter.auth.service.ConfiguredServiceClients;
import dev.ngb.platform.integration.adapter.credential.persistence.JooqCredentialReadRepository;
import dev.ngb.platform.integration.adapter.credential.persistence.JooqCredentialRepository;
import dev.ngb.platform.integration.adapter.token.persistence.PostgresRateLimiter;
import dev.ngb.platform.integration.adapter.tenantsystem.persistence.JooqTenantSystemReadRepository;
import dev.ngb.platform.integration.adapter.tenantsystem.persistence.JooqTenantSystemRepository;
import dev.ngb.platform.integration.application.auth.port.RateLimiter;
import dev.ngb.platform.integration.application.auth.port.ServiceClientRegistry;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.credential.port.CredentialRepository;
import dev.ngb.platform.integration.application.credential.port.TenantSystemScopes;
import dev.ngb.platform.integration.application.credential.query.CredentialQueries;
import dev.ngb.platform.integration.application.credential.query.CredentialReadRepository;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemQueries;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemReadRepository;
import dev.ngb.platform.integration.application.token.command.IssueTokenUseCase;
import dev.ngb.platform.integration.application.token.port.TenantCodes;
import dev.ngb.platform.integration.application.token.port.TokenIssuer;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import java.security.SecureRandom;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires the integration context: tenant systems, credentials and the token endpoint. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class IntegrationConfiguration {

    @Bean
    TenantSystemRepository tenantSystemRepository(DSLContext dsl) {
        return new JooqTenantSystemRepository(dsl);
    }

    @Bean
    TenantSystemReadRepository tenantSystemReadRepository(DSLContext dsl) {
        return new JooqTenantSystemReadRepository(dsl);
    }

    @Bean
    CredentialRepository credentialRepository(DSLContext dsl) {
        return new JooqCredentialRepository(dsl);
    }

    @Bean
    CredentialReadRepository credentialReadRepository(DSLContext dsl) {
        return new JooqCredentialReadRepository(dsl);
    }

    @Bean
    TenantSystemScopes tenantSystemScopes(PermissionCatalog catalog) {
        return new CatalogTenantSystemScopes(catalog);
    }

    @Bean
    TenantCodes tenantCodes(TenantQueries tenants) {
        return new TenantReadCodes(tenants);
    }

    @Bean
    ServiceClientRegistry serviceClientRegistry(PlatformProperties properties) {
        return new ConfiguredServiceClients(properties.serviceClients().stream()
                .map(client -> new ConfiguredServiceClients.Configured(client.id(), client.secretHash(), client.scopes()))
                .toList());
    }

    @Bean
    PostgresRateLimiter tokenRateLimiter(
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc,
            @Value("${platform.security.token-rate-limit.burst:100}") int burst,
            @Value("${platform.security.token-rate-limit.per-second:20}") double perSecond) {
        return new PostgresRateLimiter(transactionManager, jdbc, burst, perSecond);
    }

    @Bean
    ManageTenantSystemUseCase manageTenantSystemUseCase(
            UnitOfWork unitOfWork, TenantSystemRepository systems, dev.ngb.core.event.EventPublisher events,
            AuditLog auditLog, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new ManageTenantSystemUseCase(unitOfWork, systems, events, auditLog, tenantContext, ids, clock);
    }

    @Bean
    ManageCredentialUseCase manageCredentialUseCase(
            UnitOfWork unitOfWork, CredentialRepository credentials, TenantSystemRepository systems,
            TenantSystemScopes scopes, AuditLog auditLog, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new ManageCredentialUseCase(
                unitOfWork, credentials, systems, scopes, auditLog, tenantContext, ids, clock, new SecureRandom());
    }

    @Bean
    IssueTokenUseCase issueTokenUseCase(
            UnitOfWork unitOfWork, ServiceClientRegistry serviceClients, CredentialRepository credentials,
            TenantSystemRepository systems, TenantCodes tenantCodes, RateLimiter rateLimiter, TokenIssuer issuer,
            AuditLog auditLog, Clock clock,
            @Value("${platform.security.token-rate-limit.anonymous-burst:1000}") int anonymousBurst) {
        return new IssueTokenUseCase(
                unitOfWork, serviceClients, credentials, systems, tenantCodes, rateLimiter, issuer, auditLog, clock,
                anonymousBurst);
    }

    @Bean
    TenantSystemQueries tenantSystemQueries(UnitOfWork unitOfWork, TenantSystemReadRepository systems) {
        return new TenantSystemQueries(unitOfWork, systems);
    }

    @Bean
    CredentialQueries credentialQueries(
            UnitOfWork unitOfWork, CredentialReadRepository credentials, TenantSystemReadRepository systems) {
        return new CredentialQueries(unitOfWork, credentials, systems);
    }
}
