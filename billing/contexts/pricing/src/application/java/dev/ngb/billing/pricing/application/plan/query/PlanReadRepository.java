package dev.ngb.billing.pricing.application.plan.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of plans and their versions. */
public interface PlanReadRepository {

    Page<PricingPlanView> list(ListQuery query);

    Optional<PricingPlanView> findById(UUID id);
}
