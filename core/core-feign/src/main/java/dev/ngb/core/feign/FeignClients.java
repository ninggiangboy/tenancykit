package dev.ngb.core.feign;

import feign.Feign;
import feign.Request;
import feign.codec.ErrorDecoder;
import feign.http2client.Http2Client;
import tools.jackson.databind.ObjectMapper;

/** Builds one Feign client for one credential, so two callers never share a proxy. */
public final class FeignClients {

    private FeignClients() {}

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
                .target(api, stripTrailingSlash(settings.baseUrl()));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
