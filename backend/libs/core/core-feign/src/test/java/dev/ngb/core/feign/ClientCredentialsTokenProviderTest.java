package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.error.UpstreamUnavailableException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientCredentialsTokenProviderTest {

    private FakeUpstream upstream;
    private MutableClock clock;

    @BeforeEach
    void start() throws IOException {
        upstream = new FakeUpstream();
        clock = new MutableClock(Instant.parse("2026-11-13T00:00:00Z"));
    }

    @AfterEach
    void stop() {
        upstream.close();
    }

    @Test
    void exchangesTheClientCredentialsForTheAudience() {
        upstream.respond(200, "{\"access_token\":\"tok_1\",\"token_type\":\"Bearer\",\"expires_in\":600}");

        assertThat(provider().token()).isEqualTo("tok_1");

        FakeUpstream.Request request = upstream.request(0);
        assertThat(request.path()).isEqualTo("/oauth/token");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.header("Authorization")).isEqualTo("Basic " + basic("client", "secret"));
        assertThat(request.body()).isEqualTo("grant_type=client_credentials&audience=platform");
    }

    @Test
    void cachesTheTokenUntilShortlyBeforeItExpires() {
        upstream.respond(200, "{\"access_token\":\"tok_1\",\"expires_in\":600}");
        upstream.respond(200, "{\"access_token\":\"tok_2\",\"expires_in\":600}");
        ClientCredentialsTokenProvider provider = provider();

        assertThat(provider.token()).isEqualTo("tok_1");
        clock.advance(Duration.ofMinutes(8));
        assertThat(provider.token()).as("still inside the lifetime minus the skew").isEqualTo("tok_1");

        clock.advance(Duration.ofMinutes(1).plusSeconds(30));
        assertThat(provider.token()).as("refreshed before expiry").isEqualTo("tok_2");
        assertThat(upstream.requests()).hasSize(2);
    }

    @Test
    void concurrentCallersShareOneRefresh() throws Exception {
        upstream.respondAfter(Duration.ofMillis(200), 200, "{\"access_token\":\"tok_shared\",\"expires_in\":600}");
        ClientCredentialsTokenProvider provider = provider();

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return provider.token();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("tok_shared");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(upstream.requests()).as("one token request served every caller").hasSize(1);
    }

    @Test
    void invalidateForcesTheNextCallToFetchAgain() {
        upstream.respond(200, "{\"access_token\":\"tok_1\",\"expires_in\":600}");
        upstream.respond(200, "{\"access_token\":\"tok_2\",\"expires_in\":600}");
        ClientCredentialsTokenProvider provider = provider();
        assertThat(provider.token()).isEqualTo("tok_1");

        assertThat(provider.invalidate()).isTrue();

        assertThat(provider.token()).isEqualTo("tok_2");
    }

    @Test
    void aRefusedTokenRequestIsAnUpstreamFailure() {
        upstream.respond(401, "{}");

        assertThatThrownBy(() -> provider().token()).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void refusesToFetchWithoutCredentials() {
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(
                settings("", ""), clock, FeignFailures.core(), HttpClient.newHttpClient());

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(provider::token).isInstanceOf(UnauthorizedException.class);
        assertThat(upstream.requests()).isEmpty();
    }

    @Test
    void anAnswerWithoutATokenIsAnUpstreamFailure() {
        upstream.respond(200, "{\"token_type\":\"Bearer\"}");

        assertThatThrownBy(() -> provider().token()).isInstanceOf(UpstreamUnavailableException.class);
    }

    private ClientCredentialsTokenProvider provider() {
        return new ClientCredentialsTokenProvider(settings("client", "secret"), clock, FeignFailures.core());
    }

    private TokenSettings settings(String clientId, String clientSecret) {
        return TokenSettings.forBaseUrl(
                upstream.baseUrl(),
                clientId,
                clientSecret,
                "platform",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(60));
    }

    private static String basic(String id, String secret) {
        return Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
