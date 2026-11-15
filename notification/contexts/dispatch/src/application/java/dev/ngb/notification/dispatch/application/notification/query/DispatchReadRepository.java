package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.UUID;

/** The read side of the dispatch ledger, newest first, with the id as the cursor. */
public interface DispatchReadRepository {

    List<DispatchView> list(TenantId tenantId, String status, UUID before, int limit);
}
