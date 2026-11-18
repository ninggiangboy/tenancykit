package dev.ngb.loyalty.program.application.program.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.api.tier.TierView;
import dev.ngb.loyalty.program.application.program.query.ProgramReadRepository;
import dev.ngb.loyalty.program.application.promotion.query.PromotionReadRepository;
import dev.ngb.loyalty.program.application.tier.query.TierReadRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The read port other contexts use. It reads the program's own tables and hands out immutable views; nothing outside this
 * context touches them.
 *
 * <p>The promotions it returns are the active ones whose window contains the instant, stamped with the program's zone so
 * the caller can apply the weekday and time-of-day rules without knowing where the zone came from. A tenant with no
 * program reads nothing, which is the state in which nothing earns.
 */
public class ProgramReaderService implements ProgramReader {

    private final UnitOfWork unitOfWork;
    private final ProgramReadRepository programs;
    private final TierReadRepository tiers;
    private final PromotionReadRepository promotions;

    public ProgramReaderService(
            UnitOfWork unitOfWork,
            ProgramReadRepository programs,
            TierReadRepository tiers,
            PromotionReadRepository promotions) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.tiers = tiers;
        this.promotions = promotions;
    }

    @Override
    public Optional<ProgramView> program() {
        return unitOfWork.transactional(programs::find);
    }

    @Override
    public List<TierView> tiers() {
        return unitOfWork.transactional(tiers::findAll);
    }

    @Override
    public List<PromotionView> activePromotionsAt(Instant instant) {
        return unitOfWork.transactional(() -> promotions.activeAt(instant, zone()));
    }

    private ZoneId zone() {
        return programs.find().map(ProgramView::timeZone).orElse(ZoneId.of(ProgramRules.DEFAULT_TIME_ZONE));
    }
}
