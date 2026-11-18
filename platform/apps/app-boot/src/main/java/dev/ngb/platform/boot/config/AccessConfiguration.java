package dev.ngb.platform.boot.config;

import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.access.adapter.access.persistence.JooqAccessRepository;
import dev.ngb.platform.access.adapter.auth.keycloak.KeycloakAdmin;
import dev.ngb.platform.access.adapter.auth.keycloak.KeycloakConsoleClient;
import dev.ngb.platform.access.adapter.auth.keycloak.KeycloakIdentityProvider;
import dev.ngb.platform.access.adapter.catalog.yaml.YamlPermissionCatalog;
import dev.ngb.platform.access.adapter.role.persistence.JooqRoleReadRepository;
import dev.ngb.platform.access.adapter.role.persistence.JooqRoleRepository;
import dev.ngb.platform.access.adapter.user.persistence.JooqUserReadRepository;
import dev.ngb.platform.access.adapter.user.persistence.JooqUserRepository;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.auth.port.ConsoleClientPort;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.application.role.query.RoleQueries;
import dev.ngb.platform.access.application.role.query.RoleReadRepository;
import dev.ngb.platform.access.application.user.command.BootstrapPlatformAdminUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserPermissionsUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserUseCase;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.api.user.MeQueries;
import dev.ngb.platform.access.api.user.UserQueries;
import dev.ngb.platform.access.api.user.UserReadRepository;
import dev.ngb.platform.access.application.tenant.command.ProvisionTenantUseCase;
import dev.ngb.platform.tenancy.api.provisioning.TenantProvisioning;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the access context. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration {

    @Bean
    UserRepository userRepository(DSLContext dsl) {
        return new JooqUserRepository(dsl);
    }

    @Bean
    RoleRepository roleRepository(DSLContext dsl) {
        return new JooqRoleRepository(dsl);
    }

    @Bean
    AccessRepository accessRepository(DSLContext dsl) {
        return new JooqAccessRepository(dsl);
    }

    @Bean
    UserReadRepository userReadRepository(DSLContext dsl) {
        return new JooqUserReadRepository(dsl);
    }

    @Bean
    RoleReadRepository roleReadRepository(DSLContext dsl) {
        return new JooqRoleReadRepository(dsl);
    }

    @Bean
    PermissionCatalog permissionCatalog() {
        return new YamlPermissionCatalog();
    }

    /** One admin client for everything Platform asks Keycloak to change. */
    @Bean
    KeycloakAdmin keycloakAdmin(
            @Value("${platform.keycloak.base-url:http://localhost:8081}") String baseUrl,
            @Value("${platform.keycloak.realm:billing}") String realm,
            @Value("${platform.keycloak.admin-client-id:platform-admin}") String clientId,
            @Value("${platform.keycloak.admin-client-secret:}") String clientSecret) {
        return new KeycloakAdmin(baseUrl, realm, clientId, clientSecret);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(IdentityProviderPort.class)
    IdentityProviderPort identityProvider(KeycloakAdmin admin) {
        return new KeycloakIdentityProvider(admin);
    }

    /** The console client's redirect targets, which follow the tenant hosts the Platform manages. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ConsoleClientPort.class)
    ConsoleClientPort consoleClient(
            KeycloakAdmin admin,
            @Value("${platform.keycloak.console-client-id:suite-console}") String consoleClientId,
            @Value("${platform.keycloak.console-scheme:http}") String scheme,
            @Value("${platform.keycloak.console-port:}") String port) {
        return new KeycloakConsoleClient(admin, consoleClientId, scheme, port);
    }

    @Bean
    AccessPublisher accessPublisher(
            AccessRepository access, UserRepository users, EventPublisher events, IdGenerator ids, Clock clock) {
        return new AccessPublisher(access, users, events, ids, clock);
    }

    @Bean
    CreateUserUseCase createUserUseCase(
            UnitOfWork unitOfWork, UserRepository users, IdentityProviderPort identities, AccessPublisher access,
            AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new CreateUserUseCase(unitOfWork, users, identities, access, auditLog, ids, clock);
    }

    @Bean
    ManageUserUseCase manageUserUseCase(
            UnitOfWork unitOfWork, UserRepository users, AccessPublisher access, AuditLog auditLog, Clock clock) {
        return new ManageUserUseCase(unitOfWork, users, access, auditLog, clock);
    }

    @Bean
    ManageUserPermissionsUseCase manageUserPermissionsUseCase(
            UnitOfWork unitOfWork, UserRepository users, AccessRepository access, PermissionCatalog catalog,
            AccessPublisher publisher, AuditLog auditLog, Clock clock) {
        return new ManageUserPermissionsUseCase(unitOfWork, users, access, catalog, publisher, auditLog, clock);
    }

    @Bean
    CreateRoleUseCase createRoleUseCase(
            UnitOfWork unitOfWork, RoleRepository roles, PermissionCatalog catalog, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new CreateRoleUseCase(unitOfWork, roles, catalog, auditLog, ids, clock);
    }

    @Bean
    ManageRoleUseCase manageRoleUseCase(
            UnitOfWork unitOfWork, RoleRepository roles, UserRepository users, AccessRepository access, PermissionCatalog catalog,
            AccessPublisher publisher, AuditLog auditLog, Clock clock) {
        return new ManageRoleUseCase(unitOfWork, roles, users, access, catalog, publisher, auditLog, clock);
    }

    @Bean
    BootstrapPlatformAdminUseCase bootstrapPlatformAdminUseCase(
            UnitOfWork unitOfWork, CreateUserUseCase createUser, UserRepository users, RoleRepository roles, AccessRepository access,
            PermissionCatalog catalog, AccessPublisher publisher, IdGenerator ids, Clock clock) {
        return new BootstrapPlatformAdminUseCase(
                unitOfWork, createUser, users, roles, access, catalog, publisher, ids, clock);
    }

    @Bean
    ProvisionTenantUseCase provisionTenantUseCase(
            TenantProvisioning tenants, CreateUserUseCase createUser, CreateRoleUseCase createRole, AccessRepository access,
            AccessPublisher publisher, PermissionCatalog catalog, UnitOfWork unitOfWork) {
        return new ProvisionTenantUseCase(tenants, createUser, createRole, access, publisher, catalog, unitOfWork);
    }

    @Bean
    UserQueries userQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        return new UserQueries(unitOfWork, users);
    }

    @Bean
    RoleQueries roleQueries(UnitOfWork unitOfWork, RoleReadRepository roles) {
        return new RoleQueries(unitOfWork, roles);
    }

    @Bean
    MeQueries meQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        return new MeQueries(unitOfWork, users);
    }
}
