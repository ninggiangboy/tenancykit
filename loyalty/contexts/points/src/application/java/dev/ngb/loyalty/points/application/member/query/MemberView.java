package dev.ngb.loyalty.points.application.member.query;

import dev.ngb.loyalty.points.domain.member.MemberStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A member as the read side answers it: the balance and the debt, computed rather than stored, and the points that are
 * about to expire.
 */
public record MemberView(
        UUID memberId,
        String externalRef,
        String displayName,
        long available,
        long debt,
        String tierCode,
        MemberStatus status,
        Instant enrolledAt,
        List<ExpiringPoints> pointsExpiring) {

    public MemberView {
        pointsExpiring = pointsExpiring == null ? List.of() : List.copyOf(pointsExpiring);
    }
}
