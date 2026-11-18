package dev.ngb.core.feign;

import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Encodes a record body and decodes a JSON answer with the one mapper the client trusts. */
public final class JacksonCodec implements Encoder, Decoder {

    private final ObjectMapper json;
    private final String service;
    private final FeignFailures failures;

    public JacksonCodec(ObjectMapper json, String service, FeignFailures failures) {
        this.json = json;
        this.service = service;
        this.failures = failures;
    }

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) {
        if (object == null) {
            return;
        }
        try {
            template.body(json.writeValueAsBytes(object), StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            throw new IllegalStateException("a request body could not be serialized", e);
        }
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (type == void.class || type == Void.class || response.body() == null) {
            return null;
        }
        byte[] body = response.body().asInputStream().readAllBytes();
        if (body.length == 0) {
            return null;
        }
        try {
            return json.readValue(body, json.constructType(type));
        } catch (JacksonException e) {
            throw failures.unavailable(service + " answered with a body this client cannot read", e);
        }
    }
}
