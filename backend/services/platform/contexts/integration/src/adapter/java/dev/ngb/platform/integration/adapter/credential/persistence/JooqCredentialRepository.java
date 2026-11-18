package dev.ngb.platform.integration.adapter.credential.persistence;

import static dev.ngb.platform_jooq.Tables.API_CREDENTIALS;
import static dev.ngb.platform_jooq.Tables.TENANT_SYSTEMS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.application.credential.port.CredentialRepository;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import dev.ngb.platform.integration.domain.credential.CredentialStatus;
import dev.ngb.platform_jooq.tables.records.ApiCredentialsRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;

public class JooqCredentialRepository implements CredentialRepository {

    private final DSLContext dsl;

    public JooqCredentialRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(ApiCredential credential) {
        dsl.insertInto(API_CREDENTIALS)
                .set(API_CREDENTIALS.ID, credential.id().value())
                .set(API_CREDENTIALS.TENANT_ID, credential.tenantId().value())
                .set(API_CREDENTIALS.TENANT_SYSTEM_ID, credential.tenantSystemId().value())
                .set(API_CREDENTIALS.KEY_ID, credential.keyId())
                .set(API_CREDENTIALS.SECRET_HASH, credential.secretHash())
                .set(API_CREDENTIALS.SCOPES, scopes(credential))
                .set(API_CREDENTIALS.LABEL, credential.label())
                .set(API_CREDENTIALS.STATUS, credential.status().name())
                .set(API_CREDENTIALS.CREATED_AT, credential.createdAt())
                .set(API_CREDENTIALS.EXPIRES_AT, credential.expiresAt())
                .set(API_CREDENTIALS.REVOKED_AT, credential.revokedAt())
                .set(API_CREDENTIALS.VERSION, credential.version())
                .execute();
    }

    @Override
    public void update(ApiCredential credential) {
        // The guard is defence in depth: a caller that did not hold the Tenant System's lock fails loudly here
        // instead of resurrecting a revoked credential or extending one that a rotation already ended.
        int updated = dsl.update(API_CREDENTIALS)
                .set(API_CREDENTIALS.SCOPES, scopes(credential))
                .set(API_CREDENTIALS.LABEL, credential.label())
                .set(API_CREDENTIALS.STATUS, credential.status().name())
                .set(API_CREDENTIALS.EXPIRES_AT, credential.expiresAt())
                .set(API_CREDENTIALS.REVOKED_AT, credential.revokedAt())
                .set(API_CREDENTIALS.VERSION, credential.version())
                .where(API_CREDENTIALS.ID.eq(credential.id().value()))
                .and(API_CREDENTIALS.VERSION.lt(credential.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "credential " + credential.id() + " was not updated: version " + credential.version() + " is not newer");
        }
    }

    @Override
    public Optional<ApiCredential> findById(ApiCredentialId id) {
        return dsl.selectFrom(API_CREDENTIALS)
                .where(API_CREDENTIALS.ID.eq(id.value()))
                .fetchOptional(JooqCredentialRepository::toDomain);
    }

    @Override
    public List<ApiCredential> lockByTenantSystem(TenantSystemId tenantSystemId) {
        // Locking the owner row is what serialises two rotations: a lock on the credential rows alone would not see
        // the credential the other transaction is inserting, so the second rotation would read a stale set.
        dsl.select(TENANT_SYSTEMS.ID)
                .from(TENANT_SYSTEMS)
                .where(TENANT_SYSTEMS.ID.eq(tenantSystemId.value()))
                .forUpdate()
                .fetchOptional();
        return dsl.selectFrom(API_CREDENTIALS)
                .where(API_CREDENTIALS.TENANT_SYSTEM_ID.eq(tenantSystemId.value()))
                .orderBy(API_CREDENTIALS.CREATED_AT)
                .fetch(JooqCredentialRepository::toDomain);
    }

    @Override
    public Optional<ApiCredential> findByKeyId(String keyId) {
        return dsl.selectFrom(API_CREDENTIALS)
                .where(API_CREDENTIALS.KEY_ID.eq(keyId))
                .fetchOptional(JooqCredentialRepository::toDomain);
    }

    private static String[] scopes(ApiCredential credential) {
        return credential.scopes().stream().sorted().toArray(String[]::new);
    }

    private static ApiCredential toDomain(ApiCredentialsRecord row) {
        Set<String> scopes = Arrays.stream(row.getScopes())
                .collect(Collectors.toUnmodifiableSet());
        return ApiCredential.restore(
                new ApiCredentialId(row.getId()),
                new TenantId(row.getTenantId()),
                new TenantSystemId(row.getTenantSystemId()),
                row.getKeyId(),
                row.getSecretHash(),
                scopes,
                row.getLabel(),
                CredentialStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getExpiresAt(),
                row.getRevokedAt(),
                row.getVersion());
    }
}
