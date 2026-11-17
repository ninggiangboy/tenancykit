package dev.ngb.workflow.connections.application.credential.query;

import java.util.Optional;

/** The read side of the credential row; it never selects the ciphertext. */
public interface CredentialReadRepository {

    Optional<CredentialView> find();
}
