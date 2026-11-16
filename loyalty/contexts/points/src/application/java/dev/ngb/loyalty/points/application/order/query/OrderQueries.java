package dev.ngb.loyalty.points.application.order.query;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.member.service.MemberRules;

/** Reads one recorded order. An order loyalty never saw is a 404, not an empty answer. */
public class OrderQueries {

    private final UnitOfWork unitOfWork;
    private final OrderReadRepository orders;
    private final Clock clock;

    public OrderQueries(UnitOfWork unitOfWork, OrderReadRepository orders, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.clock = clock;
    }

    public OrderView get(String orderRef) {
        String ref = MemberRules.reference(orderRef, "orderRef");
        return unitOfWork.transactional(() -> orders
                .find(ref, clock.now())
                .orElseThrow(() -> PointsRefusal.notFound(
                        "ORDER_NOT_FOUND", "loyalty has not recorded that order.")));
    }
}
