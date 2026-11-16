package dev.ngb.crm.activity.application.activity.query;

import dev.ngb.core.query.Page;
import java.util.List;
import java.util.UUID;

/**
 * Reads activities straight into the view; the aggregate is never rebuilt for a read.
 *
 * <p>A timeline is paged newest first by {@code (occurredAt, id)}, so a page stays correct while newer activities are
 * appended: the cursor names the last row served and the next page starts after it.
 */
public interface ActivityReadRepository {

    /**
     * One page of a customer's timeline.
     *
     * @param limit the page size that was applied
     * @param cursor the {@code next_cursor} of the previous page, or null for the first page
     */
    Page<ActivityView> findByCustomer(UUID customerId, ActivityFilter filter, int limit, String cursor);

    /** The customer's latest activities, newest first, for a bounded slice of a read model. */
    List<ActivityView> latest(UUID customerId, int limit);
}
