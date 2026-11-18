package dev.ngb.core.feign;

import java.time.Duration;
import java.util.Objects;

/**
 * How a service authenticates to the deployment that issues its token.
 *
 * @param tokenUrl the token endpoint, normally {@code <baseUrl>/oauth/token}
 * @param clientId the service client the issuer knows
 * @param clientSecret the client's secret; it only ever travels in the token request's Basic header
 * @param audience the audience the issued token must name
 * @param connectTimeout how long opening the token connection may take
 * @param requestTimeout how long the token request may take
 * @param refreshSkew how early a cached token is refreshed before it expires
 */
public record TokenSettings(
        String tokenUrl,
        String clientId,
        String clientSecret,
        String audience,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration refreshSkew) {

    public TokenSettings {
        Objects.requireNonNull(tokenUrl, "tokenUrl");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(refreshSkew, "refreshSkew");
    }

    /** The token endpoint a deployment publishes at {@code <baseUrl>/oauth/token}. */
    public static TokenSettings forBaseUrl(
            String baseUrl,
            String clientId,
            String clientSecret,
            String audience,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration refreshSkew) {
        String trimmed = BaseUrls.withoutTrailingSlash(baseUrl);
        return new TokenSettings(
                trimmed.isBlank() ? "" : trimmed + "/oauth/token",
                clientId,
                clientSecret,
                audience,
                connectTimeout,
                requestTimeout,
                refreshSkew);
    }

    /** A half-configured client (for example no secret) must not produce a token, and never sends an empty one. */
    public boolean configured() {
        return !tokenUrl.isBlank()
                && clientId != null
                && !clientId.isBlank()
                && clientSecret != null
                && !clientSecret.isBlank();
    }

    /** The secret never appears in a log line or a bean dump. */
    @Override
    public String toString() {
        return "TokenSettings[tokenUrl=" + tokenUrl
                + ", clientId=" + clientId
                + ", clientSecret=<redacted>"
                + ", audience=" + audience
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", refreshSkew=" + refreshSkew + "]";
    }
}
