package dev.ngb.workflow.connections.api.call;

/**
 * The one door out of the suite. Only this context opens an outbound connection, which is what lets the guards,
 * redaction and the SSRF policy live in one place.
 */
public interface HttpCaller {

    OutboundResponse call(OutboundRequest request);
}
