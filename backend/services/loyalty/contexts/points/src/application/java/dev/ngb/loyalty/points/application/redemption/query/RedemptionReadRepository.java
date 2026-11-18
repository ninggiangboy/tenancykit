package dev.ngb.loyalty.points.application.redemption.query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One member's redemption, with the balance the lots give the member now. */
public interface RedemptionReadRepository {

    /** The redemption of that member, or empty when the member has none with that id. */
    Optional<RedemptionView> find(UUID memberId, UUID redemptionId, Instant now);
}
