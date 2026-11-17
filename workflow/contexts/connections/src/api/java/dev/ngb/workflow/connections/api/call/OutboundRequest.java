package dev.ngb.workflow.connections.api.call;

import java.time.Duration;
import java.util.Map;

/**
 * A call the engine wants made. The caller says what it wants and who authenticates it: an app of the suite (the token
 * is minted from the Tenant's Platform credential) or a Tenant connection (the connection supplies the credential and
 * fixes the host).
 *
 * @param path the path and query after the base address, or null when {@code url} is absolute
 * @param headers headers the node sets; an authentication header is never taken from here
 * @param idempotencyKey sent as {@code Idempotency-Key} so a retried call is recognised by the target
 */
public record OutboundRequest(
        String method,
        String app,
        String connection,
        String path,
        String url,
        Map<String, String> headers,
        Object body,
        String idempotencyKey,
        Duration timeout) {}
