package dev.ngb.workflow.connections.adapter.credential.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_PLATFORM_CREDENTIAL;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.connections.application.credential.port.CredentialRepository;
import dev.ngb.workflow.connections.domain.credential.CredentialStatus;
import dev.ngb.workflow.connections.domain.credential.PlatformCredential;
import dev.ngb.workflow_jooq.tables.records.WorkflowPlatformCredentialRecord;
import java.time.Instant;
import java.util.Optional;
import org.jooq.DSLContext;

/** The one credential row per Tenant; a registration always returns it to ACTIVE. */
public class JooqCredentialRepository implements CredentialRepository {

    private final DSLContext dsl;

    public JooqCredentialRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PlatformCredential> find() {
        return dsl.selectFrom(WORKFLOW_PLATFORM_CREDENTIAL).fetchOptional(row -> toDomain(row));
    }

    @Override
    public void upsert(PlatformCredential credential) {
        dsl.insertInto(WORKFLOW_PLATFORM_CREDENTIAL)
                .set(WORKFLOW_PLATFORM_CREDENTIAL.TENANT_ID, credential.tenantId().value())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.CLIENT_ID, credential.clientId())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.SECRET_CIPHERTEXT, credential.secretCiphertext())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.SECRET_KEY_ID, credential.secretKeyId())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.STATUS, credential.status().name())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.UPDATED_AT, credential.updatedAt())
                .onConflict(WORKFLOW_PLATFORM_CREDENTIAL.TENANT_ID)
                .doUpdate()
                .set(WORKFLOW_PLATFORM_CREDENTIAL.CLIENT_ID, credential.clientId())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.SECRET_CIPHERTEXT, credential.secretCiphertext())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.SECRET_KEY_ID, credential.secretKeyId())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.STATUS, credential.status().name())
                .set(WORKFLOW_PLATFORM_CREDENTIAL.UPDATED_AT, credential.updatedAt())
                .execute();
    }

    @Override
    public boolean markInvalid(Instant now) {
        return dsl.update(WORKFLOW_PLATFORM_CREDENTIAL)
                        .set(WORKFLOW_PLATFORM_CREDENTIAL.STATUS, CredentialStatus.INVALID.name())
                        .set(WORKFLOW_PLATFORM_CREDENTIAL.UPDATED_AT, now)
                        .where(WORKFLOW_PLATFORM_CREDENTIAL.STATUS.eq(CredentialStatus.ACTIVE.name()))
                        .execute()
                == 1;
    }

    private static PlatformCredential toDomain(WorkflowPlatformCredentialRecord row) {
        return new PlatformCredential(
                new TenantId(row.getTenantId()),
                row.getClientId(),
                row.getSecretCiphertext(),
                row.getSecretKeyId(),
                CredentialStatus.valueOf(row.getStatus()),
                row.getUpdatedAt());
    }
}
