package dev.ngb.billing.pricing.application.plan.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.UUID;

/** Reads plans for the admin GET handlers; the write side is not involved. */
public class PlanQueries {

    private final UnitOfWork unitOfWork;
    private final PlanReadRepository plans;

    public PlanQueries(UnitOfWork unitOfWork, PlanReadRepository plans) {
        this.unitOfWork = unitOfWork;
        this.plans = plans;
    }

    public Page<PricingPlanView> list(ListQuery query) {
        return unitOfWork.transactional(() -> plans.list(query));
    }

    public PricingPlanView get(UUID id) {
        return unitOfWork.transactional(() -> plans.findById(id).orElseThrow(() -> new NotFoundException("pricing plan not found")));
    }
}
