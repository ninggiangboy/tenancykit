package dev.ngb.workflow.connections.adapter.call.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.connections.application.call.port.HttpTransport;
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
        return transport.send(new HttpTransport.Request(
                method, url, Map.of(), null, Duration.ofSeconds(1), 1024));
    }
}
