package dev.ngb.mailer.email.adapter.credential.persistence;

import static dev.ngb.mailer_jooq.Tables.MAILER_CREDENTIALS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.SenderMode;
import dev.ngb.mailer.shared.FromKey;
import dev.ngb.mailer_jooq.tables.records.MailerCredentialsRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Reads and verifies credential rows. Every read carries the tenant predicate explicitly as well as relying on
 * row-level security: a lookup of "any active SHARED row" would let one tenant send from another tenant's address under
 * the suite identity, which is the whole reason credentials are per row.
 */
public class JooqCredentialStore implements CredentialStore {

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private final DSLContext dsl;

    public JooqCredentialStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Row> findActive(TenantId tenantId, FromKey fromKey) {
        return find(tenantId, fromKey, ACTIVE);
    }

    @Override
    public Optional<Row> findDisabled(TenantId tenantId, FromKey fromKey) {
        return find(tenantId, fromKey, DISABLED);
    }

    @Override
    public List<Row> findStaleActive(Instant before) {
        return dsl.selectFrom(MAILER_CREDENTIALS)
                .where(MAILER_CREDENTIALS.STATUS.eq(ACTIVE))
                .and(MAILER_CREDENTIALS.LAST_VERIFIED_AT.isNull()
                        .or(MAILER_CREDENTIALS.LAST_VERIFIED_AT.lt(before)))
                .fetch(JooqCredentialStore::toRow);
    }

    @Override
    public boolean markVerified(UUID id, long version, Instant at) {
        return dsl.update(MAILER_CREDENTIALS)
                .set(MAILER_CREDENTIALS.LAST_VERIFIED_AT, at)
                .set(MAILER_CREDENTIALS.UPDATED_AT, at)
                .where(MAILER_CREDENTIALS.ID.eq(id))
                .and(MAILER_CREDENTIALS.VERSION.eq(version))
                .execute() == 1;
    }

    private Optional<Row> find(TenantId tenantId, FromKey fromKey, String status) {
        // A rotation can leave two rows for a key; the newest version is the live one.
        return dsl.selectFrom(MAILER_CREDENTIALS)
                .where(MAILER_CREDENTIALS.TENANT_ID.eq(tenantId.value()))
                .and(MAILER_CREDENTIALS.FROM_KEY.eq(fromKey.value()))
                .and(MAILER_CREDENTIALS.STATUS.eq(status))
                .orderBy(MAILER_CREDENTIALS.VERSION.desc())
                .limit(1)
                .fetchOptional(JooqCredentialStore::toRow);
    }

    private static Row toRow(MailerCredentialsRecord row) {
        return new Row(
                row.getId(),
                new TenantId(row.getTenantId()),
                new FromKey(row.getFromKey()),
                SenderMode.valueOf(row.getMode()),
                row.getRegion(),
                row.getRoleArn(),
                row.getExternalIdCiphertext(),
                row.getAwsAccountId(),
                row.getFromAddress(),
                row.getFromName(),
                List.of(row.getReplyToAllowlist()),
                row.getVersion());
    }
}
