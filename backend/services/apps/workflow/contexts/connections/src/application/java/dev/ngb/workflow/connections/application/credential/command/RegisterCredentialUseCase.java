package dev.ngb.workflow.connections.application.credential.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.connections.application.credential.port.CredentialRepository;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.domain.credential.CredentialStatus;
import dev.ngb.workflow.connections.domain.credential.PlatformCredential;

/**
 * Stores the Tenant's Platform credential. The secret is write-only: it is encrypted on the way in and no read ever
 * returns it, so the response only says whether the credential is active.
 */
public class RegisterCredentialUseCase {

    public record Command(String clientId, String secret) {}

    public record Receipt(String clientId, String status, String keyId) {}

    private final UnitOfWork unitOfWork;
    private final CredentialRepository credentials;
    private final SecretCipher cipher;
    private final TenantContext tenantContext;
    private final Clock clock;

    public RegisterCredentialUseCase(
            UnitOfWork unitOfWork,
            CredentialRepository credentials,
            SecretCipher cipher,
            TenantContext tenantContext,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.credentials = credentials;
        this.cipher = cipher;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public Receipt execute(Command command) {
        if (command.clientId() == null || command.clientId().isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        if (command.secret() == null || command.secret().isBlank()) {
            throw new IllegalArgumentException("secret is required");
        }
        TenantId tenant = tenantContext.requireTenant();
        PlatformCredential credential = new PlatformCredential(
                tenant,
                command.clientId(),
                cipher.encrypt(command.secret()),
                cipher.keyId(),
                CredentialStatus.ACTIVE,
                clock.now());
        unitOfWork.transactional(() -> {
            credentials.upsert(credential);
            return null;
        });
        return new Receipt(credential.clientId(), credential.status().name(), credential.secretKeyId());
    }
}
