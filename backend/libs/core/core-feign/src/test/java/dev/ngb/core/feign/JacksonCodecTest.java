package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.UpstreamUnavailableException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonCodecTest {

    private final JacksonCodec codec = new JacksonCodec(FeignJson.mapper(), "Platform", FeignFailures.core());

    @Test
    void decodesARecordAndIgnoresAnUnknownField() throws Exception {
        Object decoded = codec.decode(
                response("{\"items\":[],\"next\":\"cursor\",\"addedLater\":true}"), SnapshotPage.class);

        assertThat(decoded).isInstanceOf(SnapshotPage.class);
        SnapshotPage page = (SnapshotPage) decoded;
        assertThat(page.next()).isEqualTo("cursor");
        assertThat(page.items()).isEmpty();
    }

    @Test
    void treatsAnEmptyAnswerAsNoValue() throws Exception {
        assertThat(codec.decode(response(""), SnapshotPage.class)).isNull();
    }

    @Test
    void aVoidAnswerDecodesToNull() throws Exception {
        assertThat(codec.decode(response("{\"ignored\":true}"), void.class)).isNull();
    }

    @Test
    void aBodyItCannotReadIsAnUpstreamFailure() {
        assertThatThrownBy(() -> codec.decode(response("{not json"), SnapshotPage.class))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("Platform");
    }

    @Test
    void aNullBodyIsNotEncoded() {
        RequestTemplate template = new RequestTemplate();
        codec.encode(null, SnapshotPage.class, template);

        assertThat(template.body()).isNull();
    }

    private static Response response(String body) {
        Request request = Request.create(
                Request.HttpMethod.GET, "http://platform/x", Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder()
                .status(200)
                .request(request)
                .headers(Map.of())
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    record SnapshotPage(java.util.List<Map<String, Object>> items, String next) {

        SnapshotPage {
            items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
        }
    }
}
