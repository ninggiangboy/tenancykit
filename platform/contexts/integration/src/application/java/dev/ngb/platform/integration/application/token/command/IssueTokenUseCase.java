package dev.ngb.platform.integration.application.token.command;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.integration.application.auth.exception.RateLimitExceededException;
import dev.ngb.platform.integration.application.auth.port.RateLimiter;
import dev.ngb.platform.integration.application.auth.port.ServiceClientRegistry;
import dev.ngb.platform.integration.application.credential.port.CredentialRepository;
import dev.ngb.platform.integration.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.platform.integration.application.token.port.TenantCodes;
import dev.ngb.platform.integration.application.token.port.TokenIssuer;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiKey;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client-credentials grant. A service client is authenticated against configuration; a Tenant System
 * exchanges the credential id and secret issued by {@code platform:credential:manage}. Either path answers a
 * 10-minute token whose {@code aud} is the requested app and whose {@code scope} holds only that app's codes.
 */
public class IssueTokenUseCase {

    private static final byte[] NO_SECRET = new byte[32];

    /**
     * How many buckets a request that did not authenticate shares, chosen by key id. A caller can invent client
     * ids freely, so without this every unknown id would create a rate-limit row of its own; with it the rows stay
     * bounded. It must be a power of two.
     */
    private static final int ANONYMOUS_BUCKETS = 1024;

    private static final String ANONYMOUS_BUCKET_PREFIX = "unauthenticated:";

    private final UnitOfWork unitOfWork;
    private final ServiceClientRegistry serviceClients;
    private final CredentialRepository credentials;
    private final TenantSystemRepository systems;
    private final TenantCodes tenantCodes;
    private final RateLimiter rateLimiter;
    private final TokenIssuer issuer;
    private final AuditLog auditLog;
    private final Clock clock;

    public IssueTokenUseCase(
            UnitOfWork unitOfWork,
            ServiceClientRegistry serviceClients,
            CredentialRepository credentials,
            TenantSystemRepository systems,
            TenantCodes tenantCodes,
            RateLimiter rateLimiter,
            TokenIssuer issuer,
            AuditLog auditLog,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.serviceClients = serviceClients;
        this.credentials = credentials;
        this.systems = systems;
        this.tenantCodes = tenantCodes;
        this.rateLimiter = rateLimiter;
        this.issuer = issuer;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * @param audience exactly one app code
     * @throws RateLimitExceededException when the client id exceeded its rate limit
     * @return empty when the client is unknown, the secret is wrong, the credential is revoked or expired, or the
     *     tenant system is disabled. The caller answers the same 401 for every one of them.
     */
    public Optional<TokenIssuer.Issued> issue(String clientId, String secret, String audience, String requestId) {
        Optional<TokenIssuer.Issued> issued = serviceClients.authenticate(clientId, secret)
                .map(client -> issueForServiceClient(client, audience))
                .or(() -> unitOfWork.transactional(() -> issueForTenantSystem(clientId, secret, audience)));

        // A client id spends its own bucket only when it authenticated. Everything else spends one of a fixed set of
        // shared buckets, so a wrong or invented client id cannot exhaust a real one's budget or create a row per try.
        String bucket = issued.isPresent() ? clientId : ANONYMOUS_BUCKET_PREFIX + anonymousSlot(clientId);
        if (!rateLimiter.tryAcquire(bucket)) {
            throw new RateLimitExceededException();
        }
        if (issued.isEmpty()) {
            auditFailure(clientId, audience, requestId);
        }
        return issued;
    }

    private TokenIssuer.Issued issueForServiceClient(ServiceClientRegistry.Client client, String audience) {
        Set<String> scopes = new TreeSet<>();
        client.scopes().stream().filter(code -> code.startsWith(audience + ":")).forEach(scopes::add);
        return issuer.issueService(client.id(), scopes, audience);
    }

    private Optional<TokenIssuer.Issued> issueForTenantSystem(String keyId, String secret, String audience) {
        Optional<ApiCredential> found = credentials.findByKeyId(keyId);
        if (found.isEmpty()) {
            // Spend the same work as a real comparison so a missing key id is not detectable by timing.
            MessageDigest.isEqual(ApiKey.hash(secret == null ? "" : secret), NO_SECRET);
            return Optional.empty();
        }
        ApiCredential credential = found.get();
        Instant now = clock.now();
        if (secret == null || !credential.matches(secret) || !credential.isUsable(now)) {
            return Optional.empty();
        }
        Optional<TenantSystem> system = systems.findById(credential.tenantSystemId()).filter(TenantSystem::isActive);
        if (system.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> tenantCode = tenantCodes.codeOf(credential.tenantId());
        if (tenantCode.isEmpty()) {
            return Optional.empty();
        }
        Set<String> scopes = new TreeSet<>();
        credential.scopes().stream().filter(code -> code.startsWith(audience + ":")).forEach(scopes::add);
        return Optional.of(issuer.issueTenantSystem(
                system.get().id(), credential.tenantId(), tenantCode.get(), scopes, audience));
    }

    private void auditFailure(String clientId, String audience, String requestId) {
        Instant now = clock.now();
        unitOfWork.transactional(() -> auditLog.record(AuditEntry.platform(
                "client/" + clientId,
                "token.denied",
                "oauth/token/audience/" + audience,
                requestId,
                now)));
    }

    private static int anonymousSlot(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return 0;
        }
        return Math.floorMod(clientId.hashCode(), ANONYMOUS_BUCKETS);
    }
}
