package dev.ngb.workflow.connections.application.credential.port;

import dev.ngb.workflow.connections.domain.credential.PlatformCredential;
import java.time.Instant;
import java.util.Optional;

/** The Tenant's one Platform credential. */
public interface CredentialRepository {

    Optional<PlatformCredential> find();

    /** Inserts or replaces the row; a registration always returns the credential to ACTIVE. */
    void upsert(PlatformCredential credential);

    /** Marks the credential INVALID when Platform refused its secret; false means it was already invalid. */
    boolean markInvalid(Instant now);
}
