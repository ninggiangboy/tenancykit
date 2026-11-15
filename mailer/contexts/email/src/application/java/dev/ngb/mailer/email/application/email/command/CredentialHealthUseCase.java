package dev.ngb.mailer.email.application.email.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.mailer.email.application.email.port.CredentialProbe;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.SenderMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-probes the tenant credentials whose verification has gone stale, so a revoked role is found by a scheduled job
 * instead of by a wall of dead deliveries.
 *
 * <p>The probe is a network call, so it runs between two short transactions rather than inside one: the list is read,
 * the probe happens with nothing open, and the result is written back with the row's version as a guard. That guard is
 * what keeps a probe in flight from overwriting a credential an operator rotated meanwhile.
 */
public class CredentialHealthUseCase {

    public record Swept(int checked, int failed) {}

    private final UnitOfWork unitOfWork;
    private final CredentialStore credentials;
    private final CredentialProbe probe;
    private final SecretCipher cipher;
    private final Clock clock;

    public CredentialHealthUseCase(
            UnitOfWork unitOfWork, CredentialStore credentials, CredentialProbe probe, SecretCipher cipher, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.credentials = credentials;
        this.probe = probe;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Swept runOnce(Duration staleAfter) {
        Instant cutoff = clock.now().minus(staleAfter);
        List<CredentialStore.Row> stale = unitOfWork.transactional(() -> credentials.findStaleActive(cutoff));
        if (stale.isEmpty()) {
            return new Swept(0, 0);
        }
        int failed = 0;
        for (CredentialStore.Row row : stale) {
            if (row.mode() != SenderMode.TENANT) {
                // Only an assumed role can be revoked; the suite identity is the task role itself.
                unitOfWork.transactional(() -> credentials.markVerified(row.id(), row.version(), clock.now()));
                continue;
            }
            CredentialProbe.ProbeResult result = probe(row);
            if (result.healthy()) {
                unitOfWork.transactional(() -> credentials.markVerified(row.id(), row.version(), clock.now()));
            } else {
                failed++;
            }
        }
        return new Swept(stale.size(), failed);
    }

    private CredentialProbe.ProbeResult probe(CredentialStore.Row row) {
        try {
            String externalId = cipher.decrypt(row.externalIdCiphertext(), row.id().toString());
            return probe.probe(row.roleArn(), externalId, row.region());
        } catch (RuntimeException failure) {
            // A ciphertext that no longer decrypts is a credential nobody can use; it belongs in the failure count,
            // not in an exception that would stop the sweep before the tenants behind it.
            return CredentialProbe.ProbeResult.failed(failure.getClass().getSimpleName());
        }
    }
}
