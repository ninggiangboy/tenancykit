package dev.ngb.tenant.billing.adapter.client.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.tenant.shared.error.UnauthorizedException;
import dev.ngb.tenant.shared.error.UpstreamUnavailableException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformTokenProviderTest {

    private FakePlatform platform;
    private MutableClock clock;

    @BeforeEach
    void start() throws IOException {
        platform = new FakePlatform();
        clock = new MutableClock(Instant.parse("2026-11-13T00:00:00Z"));
    }

    @AfterEach
    void stop() {
        platform.close();
    }

    @Test
    void exchangesTheClientCredentialsForTheAudienceBilling() {
        platform.respond(200, "{\"access_token\":\"tok_1\",\"token_type\":\"Bearer\",\"expires_in\":600}");

        assertThat(provider().token()).isEqualTo("tok_1");

        FakePlatform.Request request = platform.request(0);
        assertThat(request.path()).isEqualTo("/oauth/token");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.header("Authorization")).isEqualTo("Basic " + basic("client", "secret"));
        assertThat(request.body()).isEqualTo("grant_type=client_credentials&audience=billing");
    }

    @Test
    void cachesTheTokenUntilShortlyBeforeItExpires() {
        platform.respond(200, "{\"access_token\":\"tok_1\",\"expires_in\":600}");
        platform.respond(200, "{\"access_token\":\"tok_2\",\"expires_in\":600}");
        PlatformTokenProvider provider = provider();

        assertThat(provider.token()).isEqualTo("tok_1");
        clock.advance(Duration.ofMinutes(8));
        assertThat(provider.token()).as("still inside the lifetime minus the skew").isEqualTo("tok_1");

        clock.advance(Duration.ofMinutes(1).plusSeconds(30));
        assertThat(provider.token()).as("refreshed before expiry").isEqualTo("tok_2");
        assertThat(platform.requests()).hasSize(2);
    }

    @Test
    void concurrentCallersShareOneRefresh() throws Exception {
        platform.delayEachResponse(Duration.ofMillis(200));
        platform.respond(200, "{\"access_token\":\"tok_shared\",\"expires_in\":600}");
        PlatformTokenProvider provider = provider();

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
        assertThat(platform.requests()).as("one token request served every caller").hasSize(1);
    }

    @Test
    void invalidateForcesTheNextCallToFetchAgain() {
        platform.respond(200, "{\"access_token\":\"tok_1\",\"expires_in\":600}");
        platform.respond(200, "{\"access_token\":\"tok_2\",\"expires_in\":600}");
        PlatformTokenProvider provider = provider();
        assertThat(provider.token()).isEqualTo("tok_1");

        assertThat(provider.invalidate()).isTrue();

        assertThat(provider.token()).isEqualTo("tok_2");
    }

    @Test
    void aRefusedTokenRequestIsAnUpstreamFailure() {
        platform.respond(401, "{}");

        assertThatThrownBy(() -> provider().token()).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void refusesToFetchWithoutCredentials() {
        PlatformTokenProvider provider = new PlatformTokenProvider(
                settings("", ""), clock);

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(provider::token).isInstanceOf(UnauthorizedException.class);
        assertThat(platform.requests()).isEmpty();
    }

    @Test
    void anAnswerWithoutATokenIsAnUpstreamFailure() {
        platform.respond(200, "{\"token_type\":\"Bearer\"}");

        assertThatThrownBy(() -> provider().token()).isInstanceOf(UpstreamUnavailableException.class);
    }

    private PlatformTokenProvider provider() {
        return new PlatformTokenProvider(settings("client", "secret"), clock);
    }

    private PlatformTokenSettings settings(String clientId, String clientSecret) {
        return new PlatformTokenSettings(
                platform.baseUrl(), clientId, clientSecret, "billing",
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(60));
    }

    private static String basic(String id, String secret) {
        return Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** A clock the test moves by hand, so expiry is proven without waiting. */
    private static final class MutableClock implements dev.ngb.core.runtime.Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public Instant now() {
            return now.get();
        }
    }

    private static final class FakePlatform implements AutoCloseable {

        record Request(String method, String path, java.util.Map<String, List<String>> headers, String body) {
            String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(entry -> entry.getValue().get(0))
                        .findFirst()
                        .orElse(null);
            }
        }

        private record Response(int status, String body) {}

        private final HttpServer server;
        private final List<Request> requests = new ArrayList<>();
        private final List<Response> responses = new ArrayList<>();
        private Duration delay = Duration.ZERO;

        FakePlatform() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void respond(int status, String body) {
            responses.add(new Response(status, body));
        }

        void delayEachResponse(Duration duration) {
            delay = duration;
        }

        List<Request> requests() {
            return requests;
        }

        Request request(int index) {
            return requests.get(index);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders(),
                        new String(body, StandardCharsets.UTF_8)));
            }
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Response response;
            synchronized (this) {
                response = responses.isEmpty() ? new Response(500, "{}") : responses.remove(0);
            }
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
