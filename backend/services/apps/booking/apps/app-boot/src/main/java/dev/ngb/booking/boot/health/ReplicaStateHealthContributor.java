package dev.ngb.booking.boot.health;

import dev.ngb.core.replica.ReplicaState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness for the replica, reported under the health key {@code replicaState}.
 *
 * <p>Readiness is a database question, not a row count on any one stream: {@code replica_state} is written only after
 * a full rebuild finished every stream, so a rebuild in progress and a loaded replica are distinguishable. The bean is
 * named {@code replicaState} in the wiring, not after this class, because a bean named
 * {@code …ReadinessIndicator} registers under that name and an {@code include: replica} entry would match nothing and
 * fail open.
 */
public class ReplicaStateHealthContributor implements HealthIndicator {

    private final ReplicaState state;

    public ReplicaStateHealthContributor(ReplicaState state) {
        this.state = state;
    }

    @Override
    public Health health() {
        return state.rebuilt()
                ? Health.up().build()
                : Health.down()
                        .withDetail("reason", "the replica rebuild has not finished yet")
                        .build();
    }
}
