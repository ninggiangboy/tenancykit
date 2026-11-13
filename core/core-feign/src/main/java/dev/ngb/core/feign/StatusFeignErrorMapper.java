package dev.ngb.core.feign;

import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a refusal into the caller's exception, and keeps an answer retryable only when replaying the call cannot
 * change what the upstream already did. A service supplies only the mapping for its own refusals.
 *
 * <p>The retry matrix, which is {@link FeignClients#replayable(feign.Request)}'s rule:
 *
 * <table>
 *   <caption>what one answer becomes</caption>
 *   <tr><th>Status</th><th>Method</th><th>{@code Idempotency-Key}</th><th>Result</th></tr>
 *   <tr><td>{@code 5xx} or {@code 429}</td><td>safe: {@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE}</td>
 *       <td>either</td><td>{@link RetryableException}, which the retryer replays</td></tr>
 *   <tr><td>{@code 5xx} or {@code 429}</td><td>unsafe: {@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}</td>
 *       <td>sent</td><td>{@link RetryableException}: the upstream deduplicates the replay on the key</td></tr>
 *   <tr><td>{@code 5xx} or {@code 429}</td><td>unsafe</td><td>absent</td><td>{@link #refusal(int, String)}: final,
 *       because a replay could charge or create twice</td></tr>
 *   <tr><td>anything else</td><td>any</td><td>either</td><td>{@link #refusal(int, String)}: final</td></tr>
 * </table>
 *
 * <p>So an unsafe call that wants its {@code 5xx} retried must send {@link FeignClients#IDEMPOTENCY_KEY}.
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
        if (retryable(status) && FeignClients.replayable(response.request())) {
            return new RetryableException(
                    status,
                    service + " answered " + status + ": " + detail,
                    response.request().httpMethod(),
                    (Long) null,
                    response.request());
        }
        return refusal(status, detail);
    }

    /** A status an identical call could survive: the upstream failed, or asked the caller to slow down. */
    private static boolean retryable(int status) {
        return status >= 500 || status == 429;
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
