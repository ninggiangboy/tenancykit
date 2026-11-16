package dev.ngb.loyalty.points.application.member.query;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.earn.service.TierSelection;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.tier.TierView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Reads one member with what the balance cannot store: the points that expire in the next window and the tier the
 * program's list gives it today.
 *
 * <p>The default window is 30 days and the longest is a year, which is what the API document declares; the balance itself
 * is always computed from the lots, so the window only decides which of them are listed.
 */
public class MemberQueries {

    public static final int DEFAULT_EXPIRING_DAYS = 30;
    public static final int MAX_EXPIRING_DAYS = 365;

    private final UnitOfWork unitOfWork;
    private final MemberReadRepository members;
    private final ProgramReader programReader;
    private final Clock clock;

    public MemberQueries(
            UnitOfWork unitOfWork, MemberReadRepository members, ProgramReader programReader, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.programReader = programReader;
        this.clock = clock;
    }

    /** @param expiringWithinDays the window to list expiring points in, or null to take the documented default */
    public MemberDetail get(String externalRef, Integer expiringWithinDays) {
        String ref = MemberRules.externalRef(externalRef);
        int days = window(expiringWithinDays);
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            MemberView found = members
                    .find(ref, now)
                    .orElseThrow(() -> PointsRefusal.notFound(
                            "MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant."));
            MemberView member = new MemberView(
                    found.memberId(),
                    found.externalRef(),
                    found.displayName(),
                    found.available(),
                    found.debt(),
                    found.tierCode(),
                    found.status(),
                    found.enrolledAt(),
                    members.expiring(found.memberId(), now, now.plus(days, ChronoUnit.DAYS)));
            List<TierView> tiers = programReader.tiers();
            Optional<TierView> tier = TierSelection.current(member.tierCode(), tiers);
            TierView next = tier.flatMap(current -> TierSelection.next(current, tiers)).orElse(null);
            return new MemberDetail(
                    member,
                    tier.map(MemberQueries::summary).orElse(null),
                    next == null ? null : summary(next));
        });
    }

    private static MemberDetail.TierSummary summary(TierView tier) {
        return new MemberDetail.TierSummary(tier.code(), tier.name(), tier.minSpend(), tier.earnMultiplier());
    }

    private static int window(Integer expiringWithinDays) {
        if (expiringWithinDays == null) {
            return DEFAULT_EXPIRING_DAYS;
        }
        if (expiringWithinDays < 0 || expiringWithinDays > MAX_EXPIRING_DAYS) {
            throw PointsRefusal.invalid("expiringWithinDays must be within 0-" + MAX_EXPIRING_DAYS + ".");
        }
        return expiringWithinDays;
    }
}
