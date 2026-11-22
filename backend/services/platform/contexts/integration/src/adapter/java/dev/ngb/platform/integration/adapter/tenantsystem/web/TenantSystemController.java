package dev.ngb.platform.integration.adapter.tenantsystem.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.platform.sharedkernel.security.PlatformPermissions;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.credential.query.CredentialListing;
import dev.ngb.platform.integration.application.credential.query.CredentialQueries;
import dev.ngb.platform.integration.application.credential.query.CredentialView;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemListing;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemQueries;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemView;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemPurpose;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@Tag(
        name = "Tenant systems",
        description =
                "A tenant's machine callers and the API credentials they authenticate with: issue, rotate and revoke.")
@RestController
@RequestMapping("/api/v1/tenant-systems")
@Callers(Caller.TENANT_USER)
class TenantSystemController {

    record CreateSystemRequest(String name, String purpose) {}

    record UpdateSystemRequest(String action, String name) {}

    record CreateCredentialRequest(String label, List<String> scopes) {}

    record RotateRequest(Long overlapMinutes) {}

    record SystemResponse(String id, String name, String purpose, String status, Instant createdAt, long version) {

        static SystemResponse of(TenantSystem system) {
            return new SystemResponse(
                    system.id().toString(), system.name(), system.purpose().name(), system.status().name(),
                    system.createdAt(), system.version());
        }

        static SystemResponse ofView(TenantSystemView system) {
            return new SystemResponse(
                    system.id().toString(), system.name(), system.purpose(), system.status(), system.createdAt(),
                    system.version());
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

    @Operation(
            summary = "Register a tenant system",
            description =
                    "Registers a Tenant System of the current Tenant: a named machine caller with a stated `purpose` that "
                    + "authenticates with API credentials instead of a user sign-in. It has no credential yet; issue one "
                    + "with `POST /api/v1/tenant-systems/{id}/credentials`. Answers `201`. Requires "
                    + "`platform:tenant-system:manage`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.TENANT_SYSTEM_MANAGE)
    SystemResponse create(
            @RequestBody CreateSystemRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return SystemResponse.of(systems.create(
                caller(requestId), request.name(), purpose(request.purpose())));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link TenantSystemListing}. */
    @Operation(
            summary = "List tenant systems",
            description =
                    "Pages through the current Tenant's Tenant Systems, with the list-query contract's filtering, sorting "
                    + "and paging.")
    @GetMapping
    @Listing(TenantSystemListing.class)
    @RequiresPermission(PlatformPermissions.TENANT_SYSTEM_READ)
    Page<SystemResponse> list(ListQuery query) {
        return systemQueries.list(query).map(SystemResponse::ofView);
    }

    @Operation(
            summary = "Get a tenant system",
            description =
                    "Returns one Tenant System of the current Tenant with its purpose, status and version; the version is "
                    + "also the response's `ETag`. An unknown id answers `404`.")
    @GetMapping("/{id}")
    @RequiresPermission(PlatformPermissions.TENANT_SYSTEM_READ)
    ResponseEntity<SystemResponse> get(@PathVariable String id) {
        SystemResponse system = SystemResponse.ofView(systemQueries.get(TenantSystemId.of(id).value()));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(system.version())).body(system);
    }

    @Operation(
            summary = "Rename or disable a tenant system",
            description =
                    "Applies one `action` to the system: `RENAME` with the new `name`, or `DISABLE`. Each change "
                    + "publishes the system's state. Pass the system's `ETag` in `If-Match`; a stale version is refused. ")
    @PutMapping("/{id}")
    @RequiresPermission(PlatformPermissions.TENANT_SYSTEM_MANAGE)
    ResponseEntity<SystemResponse> update(
            @PathVariable String id, @RequestBody UpdateSystemRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        TenantSystem system = systems.execute(
                caller(requestId),
                new ManageTenantSystemUseCase.Command(
                        TenantSystemId.of(id), ManageTenantSystemUseCase.Action.valueOf(request.action()), request.name(),
                        ExpectedVersion.fromIfMatch(ifMatch)));
        SystemResponse body = SystemResponse.of(system);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(body.version())).body(body);
    }

    @Operation(
            summary = "Issue an API credential",
            description =
                    "Issues a credential for the Tenant System with a `label` and the `scopes` it may use. The secret "
                    + "`key` is in the response and is never shown again; storage keeps only a digest. Answers `201`. ")
    @PostMapping("/{id}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(PlatformPermissions.CREDENTIAL_MANAGE)
    IssuedCredentialResponse createCredential(
            @PathVariable String id, @RequestBody CreateCredentialRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Set<String> scopes = request.scopes() == null ? Set.of() : Set.copyOf(request.scopes());
        return issued(credentials.create(caller(requestId), TenantSystemId.of(id), request.label(), scopes));
    }

    /**
     * Filter, sort and paging follow the list-query contract; the documented parameters come from
     * {@link CredentialListing}. The path fixes the system, so its credentials are the only rows the page can hold.
     */
    @Operation(
            summary = "List a tenant system's credentials",
            description =
                    "Pages through the credentials of the Tenant System named in the path, with the list-query contract's "
                    + "filtering, sorting and paging. The secrets are never returned. Requires "
                    + "`platform:credential:manage`.")
    @GetMapping("/{id}/credentials")
    @Listing(CredentialListing.class)
    @RequiresPermission(PlatformPermissions.CREDENTIAL_MANAGE)
    Page<CredentialResponse> listCredentials(ListQuery query, @PathVariable String id) {
        return credentialQueries.list(query, TenantSystemId.of(id)).map(CredentialResponse::ofView);
    }

    @Operation(
            summary = "Rotate a tenant system's credential",
            description =
                    "Issues a new credential and lets the previous one keep working for `overlapMinutes` (24 hours when "
                    + "the body is omitted), so the Tenant System can switch over without downtime. The new secret `key` is "
                    + "in the response and is never shown again.")
    @PostMapping("/{id}/credentials/rotate")
    @RequiresPermission(PlatformPermissions.CREDENTIAL_MANAGE)
    IssuedCredentialResponse rotate(
            @PathVariable String id, @RequestBody(required = false) RotateRequest request,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        long minutes = request == null || request.overlapMinutes() == null ? DEFAULT_OVERLAP_MINUTES : request.overlapMinutes();
        return issued(credentials.rotate(caller(requestId), TenantSystemId.of(id), Duration.ofMinutes(minutes)));
    }

    @Operation(
            summary = "Revoke an API credential",
            description =
                    "Revokes one credential of the Tenant System at once; a token request with it is refused from then "
                    + "on. Answers the credential with its new state.")
    @DeleteMapping("/{systemId}/credentials/{credentialId}")
    @RequiresPermission(PlatformPermissions.CREDENTIAL_MANAGE)
    CredentialResponse revoke(
            @PathVariable String systemId, @PathVariable String credentialId,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return CredentialResponse.of(credentials.revoke(
                caller(requestId), TenantSystemId.of(systemId), ApiCredentialId.of(credentialId)));
    }

    private Audit caller(String requestId) {
        return Audit.of(currentActor.get().auditName(), requestId);
    }

    private static TenantSystemPurpose purpose(String requested) {
        return requested == null || requested.isBlank()
                ? TenantSystemPurpose.GENERIC
                : TenantSystemPurpose.valueOf(requested);
    }

    private static IssuedCredentialResponse issued(ManageCredentialUseCase.Issued issued) {
        return new IssuedCredentialResponse(CredentialResponse.of(issued.credential()), issued.plaintextKey());
    }
}
