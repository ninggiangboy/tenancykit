package dev.ngb.workflow.connections.application.credential.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.connections.application.credential.exception.CredentialUnavailableException;
import dev.ngb.workflow.connections.application.credential.port.CredentialRepository;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.application.credential.port.TokenMinter;
import dev.ngb.workflow.connections.application.credential.port.TokenProvider;
import dev.ngb.workflow.connections.application.credential.exception.TokenRejectedException;
import dev.ngb.workflow.connections.domain.credential.PlatformCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints one token per {@code (tenant, audience)} and hands it out until about a minute before it expires.
 *
 * <p>Fifty workers asking at once produce one request: the first to miss the cache publishes its in-flight future under
 * the key and the others wait on it. A 401 means the registered secret is wrong, so the credential is marked INVALID and
 * every later call fails with {@code credential_invalid} without touching Platform again.
 */
public class CachingTokenProvider implements TokenProvider {

    private static final Duration REFRESH_BEFORE = Duration.ofSeconds(60);

    private record Cached(String token, Instant refreshAt) {
        boolean fresh(Instant now) {
            return now.isBefore(refreshAt);
        }
    }

    private final CredentialRepository credentials;
    private final SecretCipher cipher;
    private final TokenMinter minter;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Cached>> inFlight = new ConcurrentHashMap<>();

    public CachingTokenProvider(
            CredentialRepository credentials, SecretCipher cipher, TokenMinter minter, Clock clock) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.minter = minter;
        this.clock = clock;
    }

    @Override
    public String token(TenantId tenant, String audience) {
        String key = tenant.value() + ":" + audience;
        Cached cached = cache.get(key);
        if (cached != null && cached.fresh(clock.now())) {
            return cached.token();
        }
        CompletableFuture<Cached> mine = new CompletableFuture<>();
        CompletableFuture<Cached> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            Cached minted = mint(tenant, audience);
            cache.put(key, minted);
            mine.complete(minted);
            return minted.token();
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Cached mint(TenantId tenant, String audience) {
        PlatformCredential credential = credentials
                .find()
                .orElseThrow(() -> new CredentialUnavailableException("credential_missing"));
        if (!credential.usable()) {
            throw new CredentialUnavailableException("credential_invalid");
        }
        String secret = cipher.decrypt(credential.secretCiphertext());
        TokenMinter.Minted minted;
        try {
            minted = minter.mint(credential.clientId(), secret, audience);
        } catch (TokenRejectedException rejected) {
            credentials.markInvalid(clock.now());
            cache.remove(tenant.value() + ":" + audience);
            throw new CredentialUnavailableException("credential_invalid");
        }
        return new Cached(minted.token(), minted.expiresAt().minus(REFRESH_BEFORE));
    }

    private static String await(CompletableFuture<Cached> running) {
        try {
            return running.join().token();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw failure;
        }
    }
}
