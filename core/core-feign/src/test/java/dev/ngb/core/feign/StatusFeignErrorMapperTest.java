package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThat;

import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
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
        assertThat(mapper.decode("key", response(503, "{\"detail\":\"out of order\"}")))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("out of order");
    }

    @Test
    void keeps429Retryable() {
        assertThat(mapper.decode("key", response(429, "{}"))).isInstanceOf(RetryableException.class);
    }

    @Test
    void aRefusalCarriesTheUpstreamsOwnSentence() {
        assertThat(mapper.decode("key", response(400, "{\"title\":\"bad cursor\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("400:bad cursor");
    }

    @Test
    void aRefusalWithoutABodyIsStillFinal() {
        assertThat(mapper.decode("key", response(404, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the request was refused");
    }

    private static Response response(int status, String body) {
        Request request = Request.create(
                Request.HttpMethod.GET, "http://platform/internal/replica/tenants", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return Response.builder()
                .status(status)
                .request(request)
                .headers(Map.of())
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }
}
