package dev.ngb.core.feign;

import dev.ngb.core.runtime.Clock;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exchanges a service client's credentials for a short-lived token, and caches it until shortly before it expires.
 * Callers share one refresh: the first one fetches while the rest wait and then read the new token, so a burst of
 * calls costs one token request.
 *
 * <p>The token is never logged, and the secret only ever travels in the Basic header of the token request.
 */
public final class ClientCredentialsTokenProvider implements TokenProvider {

    private static final String GRANT_TYPE = "client_credentials";

    private final TokenSettings settings;
    private final Clock clock;
    private final FeignFailures failures;
    private final HttpClient http;
    private final ObjectMapper json = FeignJson.mapper();
    private final ReentrantLock refresh = new ReentrantLock();

    private volatile Issued issued;

    public ClientCredentialsTokenProvider(TokenSettings settings, Clock clock, FeignFailures failures) {
        this(settings, clock, failures, HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build());
    }

    ClientCredentialsTokenProvider(
            TokenSettings settings, Clock clock, FeignFailures failures, HttpClient http) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public boolean configured() {
        return settings.configured();
    }

    @Override
    public String token() {
        Issued current = issued;
        if (current != null && clock.now().isBefore(current.expiresAt())) {
            return current.token();
        }
        return refresh();
    }

    @Override
    public boolean invalidate() {
        issued = null;
        return configured();
    }

    private String refresh() {
        refresh.lock();
        try {
            Issued current = issued;
            if (current != null && clock.now().isBefore(current.expiresAt())) {
                return current.token();
            }
            Issued fetched = fetch();
            issued = fetched;
            return fetched.token();
        } finally {
            refresh.unlock();
        }
    }

    private Issued fetch() {
        if (!configured()) {
            throw failures.refused("no client credentials are configured for audience " + settings.audience());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.tokenUrl()))
                .timeout(settings.requestTimeout())
                .header("Authorization", basic(settings.clientId(), settings.clientSecret()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw failures.unavailable("the token endpoint could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failures.unavailable("the token request was interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw failures.unavailable("the token request was refused with status " + response.statusCode());
        }
        return parse(response.body());
    }

    private Issued parse(String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException e) {
            throw failures.unavailable("the token endpoint answered with a body this client cannot read", e);
        }
        String token = node.path("access_token").asString("");
        long expiresIn = node.path("expires_in").asLong(0L);
        if (token.isBlank() || expiresIn <= 0) {
            throw failures.unavailable("the token endpoint answered without a usable token");
        }
        Instant now = clock.now();
        Duration lifetime = Duration.ofSeconds(expiresIn).minus(settings.refreshSkew());
        if (lifetime.isNegative() || lifetime.isZero()) {
            lifetime = Duration.ofSeconds(Math.max(1L, expiresIn / 2));
        }
        return new Issued(token, now.plus(lifetime));
    }

    private String form() {
        return "grant_type=" + encode(GRANT_TYPE) + "&audience=" + encode(settings.audience());
    }

    private static String basic(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Issued(String token, Instant expiresAt) {}
}
