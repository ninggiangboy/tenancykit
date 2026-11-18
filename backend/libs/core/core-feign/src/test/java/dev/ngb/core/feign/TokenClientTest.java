package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.UnauthorizedException;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenClientTest {

    private static final Request.Options OPTIONS =
            new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(1), false);

    @Test
    void refusesToCallWithoutACredential() {
        RecordingClient delegate = new RecordingClient();
        TokenClient client = new TokenClient(
                delegate, new StaticTokenProvider(""), FeignFailures.core(), "Platform");

        assertThatThrownBy(() -> client.execute(request(), OPTIONS))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Platform");
        assertThat(delegate.authorizations()).isEmpty();
    }

    @Test
    void refreshesTheTokenOnceWhenTheUpstreamRefusesIt() throws IOException {
        RecordingClient delegate = new RecordingClient(401, 200);
        TokenClient client = new TokenClient(
                delegate, new RotatingTokens(), FeignFailures.core(), "Platform");

        Response response = client.execute(request(), OPTIONS);

        assertThat(response.status()).isEqualTo(200);
        assertThat(delegate.authorizations()).containsExactly("Bearer tok_1", "Bearer tok_2");
    }

    @Test
    void givesUpAfterASecondRefusal() throws IOException {
        RecordingClient delegate = new RecordingClient(401, 401);
        TokenClient client = new TokenClient(
                delegate, new RotatingTokens(), FeignFailures.core(), "Platform");

        Response response = client.execute(request(), OPTIONS);

        assertThat(response.status()).isEqualTo(401);
        assertThat(delegate.authorizations()).hasSize(2);
    }

    @Test
    void leavesAnAcceptHeaderTheCallerSet() throws IOException {
        RecordingClient delegate = new RecordingClient(200);
        TokenClient client = new TokenClient(
                delegate, new StaticTokenProvider("tok"), FeignFailures.core(), "Platform");
        Request withAccept = Request.create(
                Request.HttpMethod.POST,
                "http://upstream/v1/things",
                Map.of("Accept", List.of("application/json")),
                null,
                StandardCharsets.UTF_8,
                null);

        client.execute(withAccept, OPTIONS);

        assertThat(delegate.requests().get(0).headers().get("Accept")).containsExactly("application/json");
        assertThat(delegate.requests().get(0).headers().get("Accept")).hasSize(1);
    }

    private static Request request() {
        return Request.create(
                Request.HttpMethod.GET,
                "http://upstream/internal/replica/tenants",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
    }

    /** Hands out a fresh token each time it is asked, like a provider whose cache keeps being invalidated. */
    private static final class RotatingTokens implements TokenProvider {

        private int issued;

        @Override
        public String token() {
            return "tok_" + (issued + 1);
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean invalidate() {
            issued++;
            return true;
        }
    }

    /** Answers from a queue and records the Authorization header each attempt carried. */
    private static final class RecordingClient implements Client {

        private final Deque<Integer> statuses = new ArrayDeque<>();
        private final List<String> authorizations = new ArrayList<>();
        private final List<Request> requests = new ArrayList<>();

        RecordingClient(int... status) {
            for (int value : status) {
                statuses.add(value);
            }
        }

        List<String> authorizations() {
            return authorizations;
        }

        List<Request> requests() {
            return requests;
        }

        @Override
        public Response execute(Request request, Request.Options options) {
            requests.add(request);
            authorizations.add(request.headers().get("Authorization").stream().findFirst().orElseThrow());
            Integer next = statuses.poll();
            int status = next == null ? 200 : next;
            return Response.builder()
                    .status(status)
                    .request(request)
                    .headers(Map.of())
                    .body("{}".getBytes(StandardCharsets.UTF_8))
                    .build();
        }
    }
}
