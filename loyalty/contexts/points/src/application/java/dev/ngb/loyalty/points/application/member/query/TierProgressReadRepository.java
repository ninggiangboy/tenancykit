package dev.ngb.loyalty.points.application.member.query;

import java.util.UUID;

/**
 * How far a member's qualifying spend is from a tier's threshold, which is the one part of the tier a member read cannot
 * work out from the tier list alone.
 *
 * <p>The window it counts over is the program's, and the month arithmetic of that window belongs to the evaluation, so
 * the read asks for the number instead of recomputing it and drifting from it.
 */
public interface TierProgressReadRepository {

    /** The spend the member still needs to reach {@code minSpend}, or zero when it has reached it already. */
    long remainingSpend(UUID memberId, long minSpend);
}
