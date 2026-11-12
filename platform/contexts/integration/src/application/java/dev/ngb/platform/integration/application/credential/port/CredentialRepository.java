package dev.ngb.platform.integration.application.credential.port;

import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import java.util.List;
import java.util.Optional;

public interface CredentialRepository {

    void add(ApiCredential credential);

    void update(ApiCredential credential);

    Optional<ApiCredential> findById(ApiCredentialId id);

    /** Finds within the current tenant only; another tenant's key id is invisible. */
    Optional<ApiCredential> findByKeyId(String keyId);

    List<ApiCredential> findByTenantSystem(TenantSystemId tenantSystemId);
}
