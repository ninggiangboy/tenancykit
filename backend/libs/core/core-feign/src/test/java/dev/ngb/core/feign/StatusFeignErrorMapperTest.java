package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThat;

import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusFeignErrorMapperTest {

    private final StatusFeignErrorMapper mapper = new StatusFeignErrorMapper("Platform", FeignJson.mapper()) {

        @Override
        protected Exception refusal(int status, String detail) {
            return new IllegalStateException(status + ":" + detail);
        }
    };

    @Test
    void keeps5xxRetryable() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.GET, 503, Map.of(), "{\"detail\":\"out of order\"}")))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("out of order");
    }

    @Test
    void keeps429Retryable() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.GET, 429, Map.of(), "{}")))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void aPostFiveHundredWithoutAnIdempotencyKeyIsNeverReplayed() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.POST, 500, Map.of(), "{}")))
                .as("a POST the upstream failed on may have created something, so it must not be sent twice")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(RetryableException.class)
                .hasMessageContaining("500");
    }

    @Test
    void aPostFiveHundredWithAnIdempotencyKeyStaysRetryable() {
        assertThat(mapper.decode(
                        "key",
                        response(
                                Request.HttpMethod.POST,
                                500,
                                Map.of(FeignClients.IDEMPOTENCY_KEY, List.of("job-1")),
                                "{}")))
                .as("a key tells the upstream to deduplicate the replay")
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void aDeleteFiveHundredWithoutAnIdempotencyKeyIsNeverReplayed() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.DELETE, 503, Map.of(), "{}")))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(RetryableException.class);
    }

    @Test
    void aRefusalCarriesTheUpstreamsOwnSentence() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.GET, 400, Map.of(), "{\"title\":\"bad cursor\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("400:bad cursor");
    }

    @Test
    void aRefusalWithoutABodyIsStillFinal() {
        assertThat(mapper.decode("key", response(Request.HttpMethod.GET, 404, Map.of(), "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the request was refused");
    }

    private static Response response(
            Request.HttpMethod method, int status, Map<String, Collection<String>> headers, String body) {
        Request request = Request.create(
                method, "http://platform/internal/replica/tenants", headers, null, StandardCharsets.UTF_8, null);
        return Response.builder()
                .status(status)
                .request(request)
                .headers(Map.of())
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }
}
