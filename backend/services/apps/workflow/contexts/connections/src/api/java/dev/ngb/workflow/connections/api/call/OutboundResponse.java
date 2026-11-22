package dev.ngb.workflow.connections.api.call;

import java.util.List;
import java.util.Map;

/**
 * What came back. A refused destination, a timeout and a non-2xx status are all results, because the engine records them
 * as an outcome rather than failing the request that asked.
 *
 * @param status the HTTP status, or null when no response arrived
 * @param body the response body, capped at one megabyte
 * @param error a short reason when {@code status} is null: {@code blocked_destination}, {@code timeout}, ...
 */
public record OutboundResponse(int status, Map<String, List<String>> headers, String body, String error) {}
