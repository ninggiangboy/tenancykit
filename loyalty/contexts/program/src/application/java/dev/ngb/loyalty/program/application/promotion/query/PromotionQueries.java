package dev.ngb.loyalty.program.application.promotion.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.query.ProgramReadRepository;
import dev.ngb.loyalty.program.application.program.service.ProgramRules;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Reads the catalog for the API's GET handlers. The program's zone travels with every row, because a promotion's
 * weekday and time-of-day rules are local to it.
 */
public class PromotionQueries {

    private final UnitOfWork unitOfWork;
    private final ProgramReadRepository programs;
    private final PromotionReadRepository promotions;

    public PromotionQueries(
            UnitOfWork unitOfWork, ProgramReadRepository programs, PromotionReadRepository promotions) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.promotions = promotions;
    }

    public Page<PromotionView> list(ListQuery query, Boolean active) {
        return unitOfWork.transactional(() -> promotions.list(query, active, zone()));
    }

    public PromotionView get(UUID id) {
        return unitOfWork.transactional(() -> promotions.findById(id, zone())
                .orElseThrow(() -> ProgramRefusal.notFound(
                        "PROMOTION_NOT_FOUND", "No promotion with that id exists in this tenant.")));
    }

    private ZoneId zone() {
        return programs.find().map(ProgramView::timeZone).orElse(ZoneId.of(ProgramRules.DEFAULT_TIME_ZONE));
    }
}
