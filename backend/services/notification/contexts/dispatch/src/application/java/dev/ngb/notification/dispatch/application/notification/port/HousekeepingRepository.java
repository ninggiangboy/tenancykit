package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.core.id.TenantId;
import java.time.Instant;

/** Bounded deletes for the tables the dispatch context owns. */
public interface HousekeepingRepository {

    int deleteNotificationsBefore(TenantId tenantId, Instant cutoff, int batchSize);

    /** Keeps the ledger row and its status, clearing the personal data it carried. */
    int redactDispatchesBefore(TenantId tenantId, Instant cutoff, int batchSize);
}
