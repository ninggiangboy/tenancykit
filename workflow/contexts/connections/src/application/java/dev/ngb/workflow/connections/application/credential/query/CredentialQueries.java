package dev.ngb.workflow.connections.application.credential.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;

/** Reads the current tenant's credential for the status handler. */
public class CredentialQueries {

    private final UnitOfWork unitOfWork;
    private final CredentialReadRepository credentials;

    public CredentialQueries(UnitOfWork unitOfWork, CredentialReadRepository credentials) {
        this.unitOfWork = unitOfWork;
        this.credentials = credentials;
    }

    public CredentialView get() {
        return unitOfWork.transactional(() -> credentials
                .find()
                .orElseThrow(() -> new NotFoundException("No platform credential is registered for this tenant.")));
    }
}
