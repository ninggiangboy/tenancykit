package dev.ngb.workflow.connections.domain.connection;

/** How a connection authenticates an outbound call. */
public enum ConnectionKind {
    BEARER,
    BASIC,
    API_KEY,
    HMAC,
    NONE
}
