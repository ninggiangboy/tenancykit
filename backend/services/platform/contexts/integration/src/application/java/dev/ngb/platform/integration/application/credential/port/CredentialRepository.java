package dev.ngb.platform.integration.application.credential.port;

import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import java.util.List;
import java.util.Optional;

public interface CredentialRepository {

    void add(ApiCredential credential);

    /** Writes the credential back; a version that is not newer than the stored one is refused. */
    void update(ApiCredential credential);

    Optional<ApiCredential> findById(ApiCredentialId id);

    /**
     * Locks the Tenant System and reads its credentials, so a rotation or a revocation decides on a set no other
     * transaction can change until it commits.
     */
    List<ApiCredential> lockByTenantSystem(TenantSystemId tenantSystemId);

    /** Finds within the current tenant only; another tenant's key id is invisible. */
    Optional<ApiCredential> findByKeyId(String keyId);
}
