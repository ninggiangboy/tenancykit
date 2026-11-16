package dev.ngb.loyalty.program.application.promotion.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's promotions. */
public interface PromotionReadRepository {

    /** One page of the catalog; {@code active} narrows it to {@code ACTIVE} or to everything else when false. */
    Page<PromotionView> list(ListQuery query, Boolean active, ZoneId zone);

    Optional<PromotionView> findById(UUID id, ZoneId zone);

    /** The active promotions whose window contains {@code instant}, highest priority first. */
    List<PromotionView> activeAt(Instant instant, ZoneId zone);
}
