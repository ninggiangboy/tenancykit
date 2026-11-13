package dev.ngb.core.feign;

import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a refusal into the caller's exception, keeping {@code 5xx} and {@code 429} retryable so the retryer sees them
 * and every other status final. A service supplies only the mapping for its own refusals.
 */
public abstract class StatusFeignErrorMapper implements ErrorDecoder {

    private final String service;
    private final ObjectMapper json;

    protected StatusFeignErrorMapper(String service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    /**
     * A status the retryer must not see again.
     *
     * @param status the refusal's status
     * @param detail the {@code detail} (or {@code title}) the upstream sent, or a generic sentence
     */
    protected abstract Exception refusal(int status, String detail);

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        String detail = detail(response);
        if (status >= 500 || status == 429) {
            return new RetryableException(
                    status,
                    service + " answered " + status + ": " + detail,
                    response.request().httpMethod(),
                    (Long) null,
                    response.request());
        }
        return refusal(status, detail);
    }

    /** The refusal's own sentence when the upstream sends one, so a caller sees the real cause. */
    protected String detail(Response response) {
        if (response.body() == null) {
            return "the request was refused";
        }
        try {
            byte[] body = response.body().asInputStream().readAllBytes();
            if (body.length == 0) {
                return "the request was refused";
            }
            JsonNode node = json.readTree(body);
            String detail = node.path("detail").asString("");
            return detail.isBlank() ? node.path("title").asString("the request was refused") : detail;
        } catch (IOException | JacksonException e) {
            return "the request was refused";
        }
    }
}
