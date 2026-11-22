package dev.ngb.workflow.connections.application.credential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.connections.application.credential.exception.CredentialUnavailableException;
import dev.ngb.workflow.connections.application.credential.port.CredentialRepository;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.application.credential.port.TokenMinter;
import dev.ngb.workflow.connections.application.credential.exception.TokenRejectedException;
import dev.ngb.workflow.connections.domain.credential.CredentialStatus;
import dev.ngb.workflow.connections.domain.credential.PlatformCredential;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The token cache: one mint serves many callers, a token is refreshed shortly before it expires, and a refused secret
 * marks the credential so nothing asks Platform again.
 */
class CachingTokenProviderTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    @Test
    void fiftyConcurrentCallersMintOneToken() throws Exception {
        CountingMinter minter = new CountingMinter(Instant.now().plusSeconds(600));
        CachingTokenProvider provider = provider(activeCredential(), minter);

        AtomicInteger served = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newFixedThreadPool(16)) {
            for (int caller = 0; caller < 50; caller++) {
                callers.submit(() -> {
                    go.await();
                    provider.token(TENANT, "crm");
                    served.incrementAndGet();
                    return null;
                });
            }
            go.countDown();
            callers.shutdown();
            assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(served.get()).isEqualTo(50);
        assertThat(minter.calls.get()).isEqualTo(1);
    }

    @Test
    void aTokenIsReusedUntilShortlyBeforeItExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CountingMinter minter = new CountingMinter(clock.now().plusSeconds(600));
        CachingTokenProvider provider = provider(activeCredential(), minter, clock);

        assertThat(provider.token(TENANT, "crm")).isEqualTo("token-1");
        clock.now = clock.now.plusSeconds(500);
        assertThat(provider.token(TENANT, "crm")).isEqualTo("token-1");
        assertThat(minter.calls.get()).isEqualTo(1);

        clock.now = clock.now.plusSeconds(60);
        assertThat(provider.token(TENANT, "crm")).isEqualTo("token-2");
        assertThat(minter.calls.get()).isEqualTo(2);
    }

    @Test
    void aRefusedSecretInvalidatesTheCredentialAndStops() {
        InMemoryCredentials credentials = new InMemoryCredentials(activeCredential());
        CountingMinter minter = new CountingMinter(Instant.now().plusSeconds(600));
        minter.reject = true;
        CachingTokenProvider provider = provider(credentials, minter, new MutableClock(Instant.now()));

        assertThatThrownBy(() -> provider.token(TENANT, "crm"))
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessage("credential_invalid");
        assertThat(credentials.credential.status()).isEqualTo(CredentialStatus.INVALID);

        assertThatThrownBy(() -> provider.token(TENANT, "crm"))
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessage("credential_invalid");
        assertThat(minter.calls.get()).as("Platform is not asked again").isEqualTo(1);
    }

    @Test
    void anUnregisteredTenantFailsWithoutACall() {
        CountingMinter minter = new CountingMinter(Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> provider(new InMemoryCredentials(null), minter, new MutableClock(Instant.now())).token(TENANT, "crm"))
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessage("credential_missing");
        assertThat(minter.calls.get()).isZero();
    }

    private static CachingTokenProvider provider(PlatformCredential credential, TokenMinter minter) {
        return provider(new InMemoryCredentials(credential), minter, new MutableClock(Instant.now()));
    }

    private static CachingTokenProvider provider(PlatformCredential credential, TokenMinter minter, Clock clock) {
        return provider(new InMemoryCredentials(credential), minter, clock);
    }

    private static CachingTokenProvider provider(
            CredentialRepository credentials, TokenMinter minter, Clock clock) {
        return new CachingTokenProvider(credentials, new ReverseCipher(), minter, clock);
    }

    private static PlatformCredential activeCredential() {
        return new PlatformCredential(
                TENANT, "client-1", "enc:secret", "v1", CredentialStatus.ACTIVE, Instant.now());
    }

    private static final class CountingMinter implements TokenMinter {
        final AtomicInteger calls = new AtomicInteger();
        final Instant expiresAt;
        boolean reject;

        CountingMinter(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        @Override
        public Minted mint(String clientId, String secret, String audience) {
            int call = calls.incrementAndGet();
            if (reject) {
                throw new TokenRejectedException();
            }
            return new Minted("token-" + call, expiresAt);
        }
    }

    private static final class InMemoryCredentials implements CredentialRepository {
        PlatformCredential credential;

        InMemoryCredentials(PlatformCredential credential) {
            this.credential = credential;
        }

        @Override
        public Optional<PlatformCredential> find() {
            return Optional.ofNullable(credential);
        }

        @Override
        public void upsert(PlatformCredential credential) {
            this.credential = credential;
        }

        @Override
        public boolean markInvalid(Instant now) {
            if (credential == null || !credential.usable()) {
                return false;
            }
            credential = credential.invalidated(now);
            return true;
        }
    }

    private static final class ReverseCipher implements SecretCipher {
        private final List<String> seen = new ArrayList<>();

        @Override
        public String encrypt(String plaintext) {
            return "enc:" + plaintext;
        }

        @Override
        public String decrypt(String envelope) {
            seen.add(envelope);
            return envelope.startsWith("enc:") ? envelope.substring(4) : envelope;
        }

        @Override
        public String keyId() {
            return "v1";
        }
    }

    private static final class MutableClock implements Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }
    }
}
