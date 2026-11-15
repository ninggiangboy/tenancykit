package dev.ngb.mailroom.audience.application.segment.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.audience.api.audience.ReceiverSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates a filter over one tenant's receivers. The adapter compiles the AST to parameterised SQL; this port is the
 * only place the sending context's campaign snapshot reaches the receivers table through a filter.
 */
public interface SegmentMatcher {

    long count(TenantId tenantId, Map<String, Object> filter);

    List<ReceiverSummary> slice(TenantId tenantId, Map<String, Object> filter, UUID afterReceiverId, int limit);
}
