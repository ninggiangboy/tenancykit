package dev.ngb.loyalty.points.application.redemption.query;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.member.query.MemberReadRepository;
import dev.ngb.loyalty.points.application.member.query.MemberView;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import java.util.UUID;

/** One redemption of one member, reached by the reference the caller knows and the id loyalty answered with. */
public class RedemptionQueries {

    private final UnitOfWork unitOfWork;
    private final MemberReadRepository members;
    private final RedemptionReadRepository redemptions;
    private final Clock clock;

    public RedemptionQueries(
            UnitOfWork unitOfWork,
            MemberReadRepository members,
            RedemptionReadRepository redemptions,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    public RedemptionView get(String externalRef, UUID redemptionId) {
        String ref = MemberRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> {
            MemberView member = members
                    .find(ref, clock.now())
                    .orElseThrow(() -> PointsRefusal.notFound(
                            "MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant."));
            return redemptions
                    .find(member.memberId(), redemptionId, clock.now())
                    .orElseThrow(() -> PointsRefusal.notFound(
                            "REDEMPTION_NOT_FOUND", "This member made no redemption with that id."));
        });
    }
}
