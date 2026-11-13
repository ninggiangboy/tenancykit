package dev.ngb.tenant.billing.adapter.client.token;

import dev.ngb.tenant.shared.error.UnauthorizedException;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the current token to every request and, when Billing refuses it with a 401, drops the cached token, fetches a
 * new one and retries exactly once. A refused credential that cannot be renewed surfaces as
 * {@link UnauthorizedException}, not as a generic upstream failure, so a caller sees the real cause.
 */
public final class TokenClient implements Client {

    private static final String AUTHORIZATION = "Authorization";
    private static final String ACCEPT = "Accept";

    private final Client delegate;
    private final TokenProvider tokens;

    public TokenClient(Client delegate, TokenProvider tokens) {
        this.delegate = delegate;
        this.tokens = tokens;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        if (!tokens.configured()) {
            throw new UnauthorizedException("this Tenant System has no credential for Billing");
        }
        Response response = delegate.execute(decorate(request, tokens.token()), options);
        if (response.status() != 401 || !tokens.invalidate()) {
            return response;
        }
        response.close();
        return delegate.execute(decorate(request, tokens.token()), options);
    }

    /** Each attempt carries its own header map, so a retry never sends the token it just had refused. */
    private static Request decorate(Request request, String token) {
        Map<String, Collection<String>> headers = new LinkedHashMap<>(request.headers());
        headers.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(AUTHORIZATION));
        headers.put(AUTHORIZATION, List.of("Bearer " + token));
        if (headers.keySet().stream().noneMatch(ACCEPT::equalsIgnoreCase)) {
            headers.put(ACCEPT, List.of("application/json"));
        }
        return Request.create(
                request.httpMethod(), request.url(), headers, request.body(), request.charset(), request.requestTemplate());
    }
}
