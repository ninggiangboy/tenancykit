package dev.ngb.workflow.connections.adapter.call.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.connections.application.call.port.HttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSRF suite: every address a Tenant must not reach is refused before a connection is made, so the test asserts the
 * short reason rather than a timeout.
 */
class GuardedHttpTransportTest {

    private final GuardedHttpTransport transport = new GuardedHttpTransport(
            false, "workflow-test", 4, 2, Duration.ofSeconds(1), Duration.ofSeconds(1), JsonMapper.builder().build());

    @Test
    void loopbackIsRefused() {
        assertRefused("http://127.0.0.1:9/anything");
        assertRefused("http://localhost:9/anything");
        assertRefused("http://[::1]:9/anything");
    }

    @Test
    void theMetadataAddressIsRefused() {
        assertRefused("http://169.254.169.254/latest/meta-data");
    }

    @Test
    void aPrivateAddressIsRefused() {
        assertRefused("http://10.0.0.1/");
        assertRefused("http://192.168.1.1/");
        assertRefused("http://172.20.0.1/");
    }

    @Test
    void oneRedirectIsFollowedAndTheSecondIsNot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/one", exchange -> {
            exchange.getResponseHeaders().add("Location", "/two");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/two", exchange -> {
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/chain", exchange -> {
            exchange.getResponseHeaders().add("Location", "/chain2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/chain2", exchange -> {
            exchange.getResponseHeaders().add("Location", "/chain3");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            // A local target is only reachable when the deployment allows non-public destinations (compose, tests).
            GuardedHttpTransport allowed = new GuardedHttpTransport(
                    true, "workflow-test", 4, 2, Duration.ofSeconds(2), Duration.ofSeconds(2),
                    JsonMapper.builder().build());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            HttpTransport.Response followed = send(allowed, "GET", base + "/one");
            assertThat(followed.status()).isEqualTo(200);
            assertThat(followed.body()).isEqualTo("ok");

            HttpTransport.Response chained = send(allowed, "GET", base + "/chain");
            assertThat(chained.status()).as("the second hop is not followed").isEqualTo(302);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anInvalidUrlIsAFailureNotAnException() {
        HttpTransport.Response response = send("GET", "not a url");
        assertThat(response.error()).isEqualTo("invalid_url");
        assertThat(response.status()).isZero();
    }

    private void assertRefused(String url) {
        HttpTransport.Response response = send("GET", url);
        assertThat(response.status()).as(url).isZero();
        assertThat(response.error()).as(url).isEqualTo("blocked_destination");
    }

    private HttpTransport.Response send(String method, String url) {
        return send(transport, method, url);
    }

    private static HttpTransport.Response send(GuardedHttpTransport transport, String method, String url) {
        return transport.send(new HttpTransport.Request(
                method, url, Map.of(), null, Duration.ofSeconds(2), 1024));
    }
}
