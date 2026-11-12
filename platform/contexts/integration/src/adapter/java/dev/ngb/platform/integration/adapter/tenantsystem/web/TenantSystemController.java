package dev.ngb.platform.integration.adapter.tenantsystem.web;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.credential.query.CredentialQueries;
import dev.ngb.platform.integration.application.credential.query.CredentialView;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemQueries;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemView;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant Systems of the current Tenant and the API credentials they authenticate with. */
@RestController
@RequestMapping("/api/v1/tenant-systems")
class TenantSystemController {

    record CreateSystemRequest(String name) {}

    record UpdateSystemRequest(String action, String name) {}

    record CreateCredentialRequest(String label, List<String> scopes) {}

    record RotateRequest(Long overlapMinutes) {}

    record SystemResponse(String id, String name, String status, Instant createdAt, long version) {

        static SystemResponse of(TenantSystem system) {
            return new SystemResponse(
                    system.id().toString(), system.name(), system.status().name(), system.createdAt(), system.version());
        }

        static SystemResponse ofView(TenantSystemView system) {
            return new SystemResponse(
                    system.id().toString(), system.name(), system.status().name(), system.createdAt(), system.version());
        }
    }

    /** Credential metadata. It never contains the secret. */
    record CredentialResponse(
            String id, String keyId, String label, Set<String> scopes, String status, Instant createdAt, Instant expiresAt, Instant revokedAt) {

        static CredentialResponse of(ApiCredential credential) {
            return new CredentialResponse(
                    credential.id().toString(), credential.keyId(), credential.label(), credential.scopes(),
                    credential.status().name(), credential.createdAt(), credential.expiresAt(), credential.revokedAt());
        }

        static CredentialResponse ofView(CredentialView credential) {
            return new CredentialResponse(
                    credential.id().toString(), credential.keyId(), credential.label(), credential.scopes(),
                    credential.status().name(), credential.createdAt(), credential.expiresAt(), credential.revokedAt());
        }
    }

    /** Returned once, when a credential is created or rotated: the only place the key appears. */
    record IssuedCredentialResponse(CredentialResponse credential, String key) {
        @Override
        public String toString() {
            return "IssuedCredentialResponse[credential=" + credential + "]";
        }
    }

    private static final long DEFAULT_OVERLAP_MINUTES = 24 * 60;

    private final ManageTenantSystemUseCase systems;
    private final TenantSystemQueries systemQueries;
    private final ManageCredentialUseCase credentials;
    private final CredentialQueries credentialQueries;
    private final CurrentActor currentActor;

    TenantSystemController(
            ManageTenantSystemUseCase systems,
            TenantSystemQueries systemQueries,
            ManageCredentialUseCase credentials,
            CredentialQueries credentialQueries,
            CurrentActor currentActor) {
        this.systems = systems;
        this.systemQueries = systemQueries;
        this.credentials = credentials;
        this.credentialQueries = credentialQueries;
        this.currentActor = currentActor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("platform:tenant-system:manage")
    SystemResponse create(
            @RequestBody CreateSystemRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return SystemResponse.of(systems.create(caller(requestId), request.name()));
    }

    @GetMapping
    @RequiresPermission("platform:tenant-system:read")
    List<SystemResponse> list() {
        return systemQueries.list().stream().map(SystemResponse::ofView).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission("platform:tenant-system:read")
    SystemResponse get(@PathVariable String id) {
        return SystemResponse.ofView(systemQueries.get(TenantSystemId.of(id).value()));
    }

    @PutMapping("/{id}")
    @RequiresPermission("platform:tenant-system:manage")
    SystemResponse update(
            @PathVariable String id, @RequestBody UpdateSystemRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return SystemResponse.of(systems.execute(
                caller(requestId),
                new ManageTenantSystemUseCase.Command(
                        TenantSystemId.of(id), ManageTenantSystemUseCase.Action.valueOf(request.action()), request.name())));
    }

    @PostMapping("/{id}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("platform:credential:manage")
    IssuedCredentialResponse createCredential(
            @PathVariable String id, @RequestBody CreateCredentialRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Set<String> scopes = request.scopes() == null ? Set.of() : Set.copyOf(request.scopes());
        return issued(credentials.create(caller(requestId), TenantSystemId.of(id), request.label(), scopes));
    }

    @GetMapping("/{id}/credentials")
    @RequiresPermission("platform:credential:manage")
    List<CredentialResponse> listCredentials(@PathVariable String id) {
        return credentialQueries.list(TenantSystemId.of(id)).stream().map(CredentialResponse::ofView).toList();
    }

    @PostMapping("/{id}/credentials/rotate")
    @RequiresPermission("platform:credential:manage")
    IssuedCredentialResponse rotate(
            @PathVariable String id, @RequestBody(required = false) RotateRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long minutes = request == null || request.overlapMinutes() == null ? DEFAULT_OVERLAP_MINUTES : request.overlapMinutes();
        return issued(credentials.rotate(caller(requestId), TenantSystemId.of(id), Duration.ofMinutes(minutes)));
    }

    @DeleteMapping("/{systemId}/credentials/{credentialId}")
    @RequiresPermission("platform:credential:manage")
    CredentialResponse revoke(
            @PathVariable String systemId, @PathVariable String credentialId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        TenantSystemId expected = TenantSystemId.of(systemId);
        ApiCredentialId target = ApiCredentialId.of(credentialId);
        boolean belongs = credentialQueries.list(expected).stream().anyMatch(c -> c.id().equals(target.value()));
        if (!belongs) {
            throw new NotFoundException("credential not found");
        }
        return CredentialResponse.of(credentials.revoke(caller(requestId), target));
    }

    private Audit caller(String requestId) {
        return Audit.of(currentActor.get().auditName(), requestId);
    }

    private static IssuedCredentialResponse issued(ManageCredentialUseCase.Issued issued) {
        return new IssuedCredentialResponse(CredentialResponse.of(issued.credential()), issued.plaintextKey());
    }
}
