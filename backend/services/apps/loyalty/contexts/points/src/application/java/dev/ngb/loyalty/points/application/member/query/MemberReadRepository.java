package dev.ngb.loyalty.points.application.member.query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The member reads: one live member by reference, and the lots that expire before a moment. */
public interface MemberReadRepository {

    /** The member and its computed balance, or empty when no live member holds that reference. */
    Optional<MemberView> find(String externalRef, Instant now);

    /** The live lots of one member expiring before {@code before}, grouped by expiry. */
    java.util.List<ExpiringPoints> expiring(UUID memberId, Instant now, Instant before);
}
