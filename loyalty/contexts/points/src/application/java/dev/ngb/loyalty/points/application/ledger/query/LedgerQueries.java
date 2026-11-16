package dev.ngb.loyalty.points.application.ledger.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.member.query.MemberReadRepository;
import dev.ngb.loyalty.points.application.member.query.MemberView;
import dev.ngb.loyalty.points.application.member.service.MemberRules;

/** A member's ledger entries, resolved from the reference the caller knows to the member loyalty owns. */
public class LedgerQueries {

    private final UnitOfWork unitOfWork;
    private final MemberReadRepository members;
    private final LedgerReadRepository ledger;
    private final Clock clock;

    public LedgerQueries(
            UnitOfWork unitOfWork, MemberReadRepository members, LedgerReadRepository ledger, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.ledger = ledger;
        this.clock = clock;
    }

    public Page<LedgerEntryView> list(String externalRef, ListQuery query) {
        String ref = MemberRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> {
            MemberView member = members
                    .find(ref, clock.now())
                    .orElseThrow(() -> PointsRefusal.notFound(
                            "MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant."));
            return ledger.page(member.memberId(), query);
        });
    }
}
