package dev.ngb.customer.activity.api.activity;

import java.util.List;
import java.util.UUID;

/**
 * The activity context's contract for another context that shows a customer's timeline: the latest activities of one
 * customer, newest first, bounded by the caller.
 *
 * <p>It is a read and nothing else: no activity can be appended through it, so a read model cannot change a customer's
 * history by reading it.
 */
public interface ActivityTimeline {

    /**
     * The customer's latest activities, newest first.
     *
     * @param limit how many to read at most; the caller picks a bound so one read stays fixed in size
     */
    List<ActivitySnapshot> latest(UUID customerId, int limit);
}
