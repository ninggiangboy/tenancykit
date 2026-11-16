package dev.ngb.crm.activity.application.activity.port;

import dev.ngb.crm.activity.domain.activity.Activity;
import java.util.Optional;

/**
 * How the write side appends an activity. An activity is written once and never rewritten; the caller's reference,
 * when it sent one, is the key a retry is recognised by.
 */
public interface ActivityRepository {

    /** The activity recorded under the caller's reference, or empty when this tenant has none. */
    Optional<Activity> findByActivityRef(String activityRef);

    /**
     * Appends the activity the caller believes is new.
     *
     * @return false when another caller already appended one under the same reference, which the caller answers by
     *     reading that row back and comparing it to what it sent
     */
    boolean insertIfAbsent(Activity activity);
}
