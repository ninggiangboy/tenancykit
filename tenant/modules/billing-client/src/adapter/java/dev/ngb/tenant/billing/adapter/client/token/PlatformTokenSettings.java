package dev.ngb.tenant.billing.adapter.client.token;

import java.time.Duration;
import java.util.Objects;

/** How this Tenant System authenticates to Platform to obtain the token Billing accepts. */
public record PlatformTokenSettings(
        String baseUrl,
        String clientId,
        String clientSecret,
        String audience,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration refreshSkew) {

    public PlatformTokenSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(refreshSkew, "refreshSkew");
    }

    /** A half-configured client (for example no secret) must not produce a token, and never sends an empty one. */
    public boolean configured() {
        return !baseUrl.isBlank()
                && clientId != null
                && !clientId.isBlank()
                && clientSecret != null
                && !clientSecret.isBlank();
    }

    String tokenUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/oauth/token";
    }
}
