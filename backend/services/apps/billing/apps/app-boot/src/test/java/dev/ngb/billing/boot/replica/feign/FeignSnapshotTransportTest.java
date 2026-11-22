package dev.ngb.billing.boot.replica.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.error.UpstreamUnavailableException;
import dev.ngb.core.feign.ClientCredentialsTokenProvider;
import dev.ngb.core.feign.FeignFailures;
import dev.ngb.core.feign.FeignSettings;
import dev.ngb.core.feign.StaticTokenProvider;
import dev.ngb.core.feign.TokenProvider;
import dev.ngb.core.feign.TokenSettings;
import dev.ngb.core.sync.SnapshotClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeignSnapshotTransportTest {

    private FakePlatform platform;

    @BeforeEach
    void start() throws IOException {
        platform = new FakePlatform();
    }

    @AfterEach
    void stop() {
        platform.close();
    }

    @Test
    void pagesAStreamWithItsCursorAndTheCredential() {
        platform.respond(200, "{\"items\":[{\"id\":\"t1\"},{\"id\":\"t2\"}],\"next\":\"cursor-2\"}");

        SnapshotClient.Page page = transport(new StaticTokenProvider("tok"), 2)
                .page("tenants", "cursor-1", 100, "ignored");

        assertThat(page.items()).hasSize(2);
        assertThat(page.next()).isEqualTo("cursor-2");
        FakePlatform.Request request = platform.snapshotRequest(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).startsWith("/internal/replica/tenants");
        assertThat(request.path()).contains("limit=100", "after=cursor-1");
        assertThat(request.header("Authorization")).isEqualTo("Bearer tok");
        assertThat(request.header("Accept")).isEqualTo("application/json");
    }

    @Test
    void omitsTheCursorWhenThereIsNone() {
        platform.respond(200, "{\"items\":[],\"next\":null}");

        SnapshotClient.Page page = transport(new StaticTokenProvider("tok"), 2).page("tenants", null, 50, "ignored");

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
        assertThat(platform.snapshotRequest(0).path()).doesNotContain("after=");
    }

    @Test
    void anEmptyAnswerIsAnEmptyPage() {
        platform.respond(200, "");

        SnapshotClient.Page page = transport(new StaticTokenProvider("tok"), 2).page("tenants", null, 10, "ignored");

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void retriesUntilTheAttemptBudgetIsSpent() {
        platform.respond(503, "{}");
        platform.respond(503, "{}");
        platform.respond(503, "{}");

        assertThatThrownBy(() -> transport(new StaticTokenProvider("tok"), 3).page("tenants", null, 10, "ignored"))
                .isInstanceOf(UpstreamUnavailableException.class);

        assertThat(platform.snapshotRequests()).hasSize(3);
    }

    @Test
    void recoversWhenASingleAttemptFails() {
        platform.respond(503, "{}");
        platform.respond(200, "{\"items\":[{\"id\":\"t1\"}],\"next\":null}");

        SnapshotClient.Page page = transport(new StaticTokenProvider("tok"), 3).page("tenants", null, 10, "ignored");

        assertThat(page.items()).hasSize(1);
        assertThat(platform.snapshotRequests()).hasSize(2);
    }

    @Test
    void aRefusedRequestIsFinal() {
        platform.respond(404, "{\"detail\":\"unknown replica stream\"}");

        assertThatThrownBy(() -> transport(new StaticTokenProvider("tok"), 3)
                        .page("unknown", null, 10, "ignored"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown replica stream");

        assertThat(platform.snapshotRequests()).as("a 4xx is never retried").hasSize(1);
    }

    @Test
    void refreshesTheTokenOnceWhenPlatformRefusesIt() {
        platform.respondToken(200, "{\"access_token\":\"tok_1\",\"expires_in\":600}");
        platform.respondToken(200, "{\"access_token\":\"tok_2\",\"expires_in\":600}");
        platform.respond(401, "{}");
        platform.respond(200, "{\"items\":[{\"id\":\"t1\"}],\"next\":null}");

        SnapshotClient.Page page = transport(platformTokens(), 2).page("tenants", null, 10, "ignored");

        assertThat(page.items()).hasSize(1);
        assertThat(platform.snapshotRequests()).hasSize(2);
        assertThat(platform.snapshotRequest(0).header("Authorization")).isEqualTo("Bearer tok_1");
        assertThat(platform.snapshotRequest(1).header("Authorization")).isEqualTo("Bearer tok_2");
        assertThat(platform.tokenRequests()).hasSize(2);
    }

    private FeignSnapshotTransport transport(TokenProvider tokens, int maxAttempts) {
        return FeignSnapshotTransport.create(settings(maxAttempts), tokens, FeignFailures.core());
    }

    private FeignSettings settings(int maxAttempts) {
        return new FeignSettings(
                "Platform",
                platform.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                maxAttempts,
                Duration.ofMillis(1));
    }

    private TokenProvider platformTokens() {
        TokenSettings settings = TokenSettings.forBaseUrl(
                platform.baseUrl(),
                "billing",
                "secret",
                "platform",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30));
        return new ClientCredentialsTokenProvider(settings, Instant::now, FeignFailures.core());
    }

    /** A local Platform: the token endpoint answers from its queue, everything else is a snapshot page. */
    private static final class FakePlatform implements AutoCloseable {

        record Request(String method, String path, Map<String, List<String>> headers) {

            String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(entry -> entry.getValue().get(0))
                        .findFirst()
                        .orElse(null);
            }
        }

        private record Answer(int status, String body) {}

        private final HttpServer server;
        private final Deque<Answer> snapshots = new ArrayDeque<>();
        private final Deque<Answer> tokens = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        FakePlatform() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void respond(int status, String body) {
            snapshots.add(new Answer(status, body));
        }

        void respondToken(int status, String body) {
            tokens.add(new Answer(status, body));
        }

        List<Request> snapshotRequests() {
            return requests.stream().filter(request -> !request.path().equals("/oauth/token")).toList();
        }

        List<Request> tokenRequests() {
            return requests.stream().filter(request -> request.path().equals("/oauth/token")).toList();
        }

        Request snapshotRequest(int index) {
            return snapshotRequests().get(index);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        path + (query == null ? "" : "?" + query),
                        new LinkedHashMap<>(exchange.getRequestHeaders())));
            }
            Answer answer;
            synchronized (this) {
                Deque<Answer> queue = path.equals("/oauth/token") ? tokens : snapshots;
                Answer next = queue.poll();
                answer = next == null ? new Answer(500, "{}") : next;
            }
            byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
