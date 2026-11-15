package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.shared.FromKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the credential a (tenant, from-key) pair resolves to. Every lookup carries the tenant predicate: a lookup
 * without it would let one tenant send from another tenant's address under the suite identity, which is the whole
 * reason credentials are per row.
 */
public interface CredentialStore {

    /** The active row for this tenant and key, if the tenant registered one. */
    Optional<Row> findActive(TenantId tenantId, FromKey fromKey);

    /** A row that exists but is disabled. It is an error rather than a fallback: silently switching identity is worse than failing. */
    Optional<Row> findDisabled(TenantId tenantId, FromKey fromKey);

    /** Active rows whose verification is older than {@code before}, so a revoked role is found by the probe rather than by a dead delivery. */
    List<Row> findStaleActive(Instant before);

    /** Records a successful probe. The version guards against overwriting a row that was rotated meanwhile. */
    boolean markVerified(UUID id, long version, Instant at);

    /**
     * One credential row.
     *
     * @param externalIdCiphertext the encrypted external id; it decrypts only with the row id as context
     * @param version the optimistic-lock version, also the cache key that makes a rotation visible without a restart
     */
    record Row(
            UUID id,
            TenantId tenantId,
            FromKey fromKey,
            SenderMode mode,
            String region,
            String roleArn,
            String externalIdCiphertext,
            String awsAccountId,
            String fromAddress,
            String fromName,
            List<String> replyToAllowlist,
            long version) {

        public Row {
            replyToAllowlist = replyToAllowlist == null ? List.of() : List.copyOf(replyToAllowlist);
        }
    }
}
