package dev.ngb.workflow.connections.adapter.credential.http;

import dev.ngb.core.webhook.WebhookHttpSender;
import dev.ngb.workflow.connections.application.credential.port.TokenMinter;
import dev.ngb.workflow.connections.application.credential.exception.TokenRejectedException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Platform token endpoint: one POST with HTTP Basic and {@code grant_type=client_credentials}.
 *
 * <p>A 401 fails at once and never retries, because a wrong secret stays wrong. A 429 answers with
 * {@code Retry-After}, which the provider reports as a delay instead of hammering the endpoint.
 */
public class PlatformTokenClient implements TokenMinter {

    private final WebhookHttpSender sender;
    private final String tokenUrl;
    private final JsonMapper json;

    public PlatformTokenClient(WebhookHttpSender sender, String tokenUrl, JsonMapper json) {
        this.sender = sender;
        this.tokenUrl = tokenUrl;
        this.json = json;
    }

    @Override
    public Minted mint(String clientId, String secret, String audience) {
        String form = "grant_type=client_credentials&audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "Authorization",
                "Basic " + Base64.getEncoder()
                        .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)),
                "Content-Type",
                "application/x-www-form-urlencoded");
        WebhookHttpSender.Result result = sender.send(new WebhookHttpSender.Request(
                tokenUrl, headers, form.getBytes(StandardCharsets.UTF_8)));
        if (result.statusCode() != null && result.statusCode() == 401) {
            throw new TokenRejectedException();
        }
        if (result.statusCode() != 200) {
            throw new IllegalStateException("the token endpoint answered " + result.statusCode() + " " + result.error());
        }
        Map<String, Object> body = json.readValue(result.responseExcerpt(), new TypeReference<Map<String, Object>>() {});
        Object token = body.get("access_token");
        Object ttl = body.get("expires_in");
        if (!(token instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("the token endpoint returned no access token");
        }
        long seconds = ttl instanceof Number number ? number.longValue() : 600;
        return new Minted(text, Instant.now().plus(Duration.ofSeconds(seconds)));
    }
}
