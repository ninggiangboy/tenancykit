package dev.ngb.crm.activity.adapter.activity.persistence;

import dev.ngb.crm.activity.api.activity.ActivitySnapshot;
import dev.ngb.crm.activity.api.activity.ActivityTimeline;
import dev.ngb.crm.activity.application.activity.query.ActivityReadRepository;
import dev.ngb.crm.activity.application.activity.query.ActivityView;
import java.util.List;
import java.util.UUID;

/**
 * The activity context's seam for a read model that shows one customer's timeline: a bounded, indexed slice of the
 * activities, newest first.
 *
 * <p>It reads through the context's own read port, so the timeline a caller receives and the timeline the listing
 * endpoint pages are the same projection of the same rows.
 */
public class JooqActivityTimeline implements ActivityTimeline {

    private final ActivityReadRepository activities;

    public JooqActivityTimeline(ActivityReadRepository activities) {
        this.activities = activities;
    }

    @Override
    public List<ActivitySnapshot> latest(UUID customerId, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return activities.latest(customerId, limit).stream()
                .map(JooqActivityTimeline::snapshot)
                .toList();
    }

    private static ActivitySnapshot snapshot(ActivityView view) {
        return new ActivitySnapshot(
                view.id(),
                view.customerId(),
                view.type(),
                view.occurredAt(),
                view.source(),
                view.payload(),
                view.activityRef(),
                view.createdAt());
    }
}
