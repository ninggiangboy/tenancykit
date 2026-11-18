package dev.ngb.core.feign;

import dev.ngb.core.http.HttpHeaders;
import feign.Feign;
import feign.Request;
import feign.codec.ErrorDecoder;
import feign.http2client.Http2Client;
import tools.jackson.databind.ObjectMapper;

/** Builds one Feign client for one credential, so two callers never share a proxy. */
public final class FeignClients {

    /**
     * The header an unsafe call sets to tell the upstream it may see the same call twice, because it deduplicates on
     * this key. Without it a refused POST, PUT or PATCH is never replayed.
     */
    public static final String IDEMPOTENCY_KEY = HttpHeaders.IDEMPOTENCY_KEY;

    private FeignClients() {}

    /**
     * Whether replaying a request cannot change what the upstream already did: a safe method — {@code GET},
     * {@code HEAD}, {@code OPTIONS} or {@code TRACE} — or an unsafe one that carries an {@link #IDEMPOTENCY_KEY}.
     * A request that cannot be judged is not replayable.
     *
     * <p>This is the one place the rule lives: {@link StatusFeignErrorMapper} asks it before it makes an answer
     * retryable, so a POST that was refused with a 5xx becomes the caller's upstream failure instead of a second call.
     */
    public static boolean replayable(Request request) {
        if (request == null) {
            return false;
        }
        return safeMethod(request.httpMethod()) || carriesIdempotencyKey(request);
    }

    private static boolean safeMethod(Request.HttpMethod method) {
        return method == Request.HttpMethod.GET
                || method == Request.HttpMethod.HEAD
                || method == Request.HttpMethod.OPTIONS
                || method == Request.HttpMethod.TRACE;
    }

    private static boolean carriesIdempotencyKey(Request request) {
        return request.headers().keySet().stream().anyMatch(IDEMPOTENCY_KEY::equalsIgnoreCase);
    }

    /**
     * @param api the interface holding the paths, written against the upstream's contract
     * @param settings where the upstream is and how patient the client is
     * @param tokens the credential every request carries
     * @param failures the caller's exception model, used when no answer was usable
     * @param errors the caller's mapping for refusals
     */
    public static <T> T create(
            Class<T> api,
            FeignSettings settings,
            TokenProvider tokens,
            FeignFailures failures,
            ErrorDecoder errors) {
        ObjectMapper json = FeignJson.mapper();
        JacksonCodec codec = new JacksonCodec(json, settings.service(), failures);
        return Feign.builder()
                .client(new TokenClient(new Http2Client(), tokens, failures, settings.service()))
                .contract(new SpringMvcFeignContract())
                .encoder(codec)
                .decoder(codec)
                .errorDecoder(errors)
                .retryer(new LinearRetryer(settings.maxAttempts(), settings.retryBackoff().toMillis(), failures))
                .options(new Request.Options(settings.connectTimeout(), settings.readTimeout(), false))
                .target(api, BaseUrls.withoutTrailingSlash(settings.baseUrl()));
    }
}
