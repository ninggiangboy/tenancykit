package dev.ngb.mailer.email;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import dev.ngb.mailer.email.application.email.command.CredentialHealthUseCase;
import dev.ngb.mailer.email.application.email.port.CredentialProbe;
import dev.ngb.mailer.email.application.email.port.CredentialStore;
import dev.ngb.mailer.email.application.email.port.SenderMode;
import dev.ngb.mailer.shared.FromKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The health sweep: a healthy credential is re-verified, a revoked one is counted and left alone, and neither case
 * throws out of the sweep — one broken tenant must not stop the probe for the tenants behind it.
 */
class CredentialHealthUseCaseTest {

    private static final String MASTER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final SecretCipher cipher = new AesGcmSecretCipher("mailer.secrets", MASTER_KEY, "k1");
    private final InMemoryStore store = new InMemoryStore();
    private final Instant now = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void aHealthyCredentialIsReverified() {
        CredentialStore.Row row = tenantRow("111122223333");
        store.stale.add(row);
        ProbeAnswer answer = new ProbeAnswer(CredentialProbe.ProbeResult.healthy(50_000.0, 14.0));

        CredentialHealthUseCase.Swept swept = useCase(answer).runOnce(Duration.ofHours(1));

        assertThat(swept.checked()).isEqualTo(1);
        assertThat(swept.failed()).isZero();
        assertThat(store.verified).containsExactly(row.id());
    }

    @Test
    void aRevokedRoleIsCountedAndNotMarkedVerified() {
        CredentialStore.Row row = tenantRow("111122223333");
        store.stale.add(row);
        ProbeAnswer answer = new ProbeAnswer(CredentialProbe.ProbeResult.failed("AccessDenied"));

        CredentialHealthUseCase.Swept swept = useCase(answer).runOnce(Duration.ofHours(1));

        assertThat(swept.checked()).isEqualTo(1);
        assertThat(swept.failed()).isEqualTo(1);
        assertThat(store.verified).isEmpty();
    }

    @Test
    void anUndecryptableCiphertextFailsThatRowWithoutStoppingTheRest() {
        CredentialStore.Row broken = tenantRowWithCiphertext("111122223333", "not-a-ciphertext");
        CredentialStore.Row healthy = tenantRow("444455556666");
        store.stale.add(broken);
        store.stale.add(healthy);
        ProbeAnswer answer = new ProbeAnswer(CredentialProbe.ProbeResult.healthy(null, null));

        CredentialHealthUseCase.Swept swept = useCase(answer).runOnce(Duration.ofHours(1));

        assertThat(swept.checked()).isEqualTo(2);
        assertThat(swept.failed()).isEqualTo(1);
        assertThat(store.verified).containsExactly(healthy.id());
    }

    private CredentialHealthUseCase useCase(CredentialProbe probe) {
        return new CredentialHealthUseCase(new DirectUnitOfWork(), store, probe, cipher, () -> now);
    }

    private CredentialStore.Row tenantRow(String account) {
        UUID id = UUID.randomUUID();
        return new CredentialStore.Row(
                id,
                new TenantId(UUID.randomUUID()),
                new FromKey("marketing"),
                SenderMode.TENANT,
                "eu-west-1",
                "arn:aws:iam::" + account + ":role/mailer",
                cipher.encrypt("external-id", id.toString()),
                account,
                "billing@example.com",
                null,
                List.of(),
                1);
    }

    private CredentialStore.Row tenantRowWithCiphertext(String account, String ciphertext) {
        UUID id = UUID.randomUUID();
        return new CredentialStore.Row(
                id,
                new TenantId(UUID.randomUUID()),
                new FromKey("marketing"),
                SenderMode.TENANT,
                "eu-west-1",
                "arn:aws:iam::" + account + ":role/mailer",
                ciphertext,
                account,
                "billing@example.com",
                null,
                List.of(),
                1);
    }

    /** The sweep's transactions are irrelevant here; this keeps the test focused on its decisions. */
    private static final class DirectUnitOfWork implements UnitOfWork {

        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    }

    private static final class ProbeAnswer implements CredentialProbe {

        private final ProbeResult result;

        ProbeAnswer(ProbeResult result) {
            this.result = result;
        }

        @Override
        public ProbeResult probe(String roleArn, String externalId, String region) {
            return result;
        }
    }

    private static final class InMemoryStore implements CredentialStore {

        private final List<Row> stale = new ArrayList<>();
        private final List<UUID> verified = new ArrayList<>();

        @Override
        public Optional<Row> findActive(TenantId tenantId, FromKey fromKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Row> findDisabled(TenantId tenantId, FromKey fromKey) {
            return Optional.empty();
        }

        @Override
        public List<Row> findStaleActive(Instant before) {
            return List.copyOf(stale);
        }

        @Override
        public boolean markVerified(UUID id, long version, Instant at) {
            verified.add(id);
            return true;
        }
    }
}
