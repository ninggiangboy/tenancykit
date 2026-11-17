package dev.ngb.workflow.connections.application.credential.query;

import java.time.Instant;

/** The registered credential as a read answers it: the client id and the state, never the secret. */
public record CredentialView(String clientId, String status, String keyId, Instant updatedAt) {}
