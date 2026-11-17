package dev.ngb.workflow.connections.application.call.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.connections.api.call.HttpCaller;
import dev.ngb.workflow.connections.api.call.OutboundRequest;
import dev.ngb.workflow.connections.api.call.OutboundResponse;
import dev.ngb.workflow.connections.application.call.exception.CallNotAllowedException;
import dev.ngb.workflow.connections.application.call.port.HttpTransport;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.application.credential.port.TokenProvider;
import dev.ngb.workflow.connections.application.connection.port.ConnectionRepository;
import dev.ngb.workflow.connections.domain.connection.Connection;
import dev.ngb.workflow.platform.api.replica.TenantDomains;
import dev.ngb.workflow.connections.domain.connection.ConnectionKind;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one implementation of the outbound door: builds the absolute URL, authenticates it, and hands the bytes to the
 * guarded transport.
 *
 * <p>An app call is authenticated by a token minted for that audience, so the Tenant's credential never leaves this
 * context. A connection call is limited to the connection's host and uses the connection's own secret. The token is
 * fetched outside the read's transaction, because network time must not be held under a database lock.
 */
public class HttpCallerService implements HttpCaller {

    private static final int MAX_BODY_BYTES = 1_048_576;

    private final UnitOfWork unitOfWork;
    private final ConnectionRepository connections;
    private final TokenProvider tokens;
    private final SecretCipher cipher;
    private final HttpTransport transport;
    private final TenantContext tenantContext;
    private final TenantDomains domains;
    private final Map<String, String> appBaseUrls;

    public HttpCallerService(
            UnitOfWork unitOfWork,
            ConnectionRepository connections,
            TokenProvider tokens,
            SecretCipher cipher,
            HttpTransport transport,
            TenantContext tenantContext,
            TenantDomains domains,
            Map<String, String> appBaseUrls) {
        this.unitOfWork = unitOfWork;
        this.connections = connections;
        this.tokens = tokens;
        this.cipher = cipher;
        this.transport = transport;
        this.tenantContext = tenantContext;
        this.domains = domains;
        this.appBaseUrls = Map.copyOf(appBaseUrls);
    }

    @Override
    public OutboundResponse call(OutboundRequest request) {
        Map<String, String> headers = new LinkedHashMap<>(request.headers() == null ? Map.of() : request.headers());
        String url;
        if (request.connection() != null) {
            Connection connection = unitOfWork
                    .transactional(() -> connections.findByName(request.connection()))
                    .orElseThrow(() -> new CallNotAllowedException("connection_unknown"));
            url = absolute(connection, request);
            headers.putAll(connectionHeader(connection));
            headers.putAll(connection.headers());
        } else if (request.app() != null) {
            String base = appBaseUrls.get(request.app());
            if (base == null) {
                throw new CallNotAllowedException("app_unknown");
            }
            TenantId tenant = tenantContext.requireTenant();
            String host = domains.hostname(tenant).orElseThrow(() -> new CallNotAllowedException("tenant_domain_missing"));
            url = join(base.replace("{host}", host), request.path());
            headers.put("Authorization", "Bearer " + tokens.token(tenant, request.app()));
        } else {
            url = request.url();
            if (url == null) {
                throw new CallNotAllowedException("call_has_no_target");
            }
        }
        headers.remove("Idempotency-Key");
        if (request.idempotencyKey() != null) {
            headers.put("Idempotency-Key", request.idempotencyKey());
        }
        HttpTransport.Response response = transport.send(new HttpTransport.Request(
                request.method() == null ? "GET" : request.method().toUpperCase(),
                url,
                headers,
                request.body(),
                request.timeout() == null ? Duration.ofSeconds(30) : request.timeout(),
                MAX_BODY_BYTES));
        return new OutboundResponse(response.status(), response.headers(), response.body(), response.error());
    }

    private String absolute(Connection connection, OutboundRequest request) {
        String url = request.url() != null ? request.url() : join("https://" + connection.allowedHost(), request.path());
        String host = URI.create(url).getHost();
        if (!connection.allows(host)) {
            throw new CallNotAllowedException("host_not_allowed");
        }
        return url;
    }

    private Map<String, String> connectionHeader(Connection connection) {
        if (connection.kind() == ConnectionKind.NONE) {
            return Map.of();
        }
        if (!connection.hasSecret()) {
            throw new CallNotAllowedException("connection_has_no_secret");
        }
        String secret = cipher.decrypt(connection.secretCiphertext());
        return switch (connection.kind()) {
            case BEARER -> Map.of("Authorization", "Bearer " + secret);
            case BASIC -> Map.of(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> Map.of("X-API-Key", secret);
            case HMAC, NONE -> throw new CallNotAllowedException("connection_kind_unsupported");
        };
    }

    private static String join(String base, String path) {
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (path == null || path.isBlank()) {
            return root;
        }
        return path.startsWith("/") ? root + path : root + "/" + path;
    }
}
