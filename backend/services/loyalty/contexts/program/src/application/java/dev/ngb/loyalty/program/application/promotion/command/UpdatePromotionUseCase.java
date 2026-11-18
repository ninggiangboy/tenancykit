package dev.ngb.loyalty.program.application.promotion.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.promotion.port.PromotionRepository;
import dev.ngb.loyalty.program.application.promotion.service.PromotionRules;
import dev.ngb.loyalty.program.domain.promotion.Promotion;
import dev.ngb.loyalty.program.domain.promotion.PromotionConditions;
import dev.ngb.loyalty.program.domain.promotion.PromotionId;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Changes the fields a patch names and leaves the rest as they are. The merged promotion is validated as a whole, so a
 * patch cannot move a window past its other end or drop the multiplier below its floor.
 */
public class UpdatePromotionUseCase {

    /** A null field is left as it is; an empty condition set is an explicit "no restriction". */
    public record Command(
            String name,
            Integer priority,
            String status,
            Boolean active,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            PromotionConditions conditions) {}

    private final UnitOfWork unitOfWork;
    private final PromotionRepository promotions;
    private final Clock clock;

    public UpdatePromotionUseCase(UnitOfWork unitOfWork, PromotionRepository promotions, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.promotions = promotions;
        this.clock = clock;
    }

    public Promotion execute(PromotionId id, Command change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Promotion promotion = promotions.lockById(id)
                    .orElseThrow(() -> ProgramRefusal.notFound(
                            "PROMOTION_NOT_FOUND", "No promotion with that id exists in this tenant."));
            expected.check(promotion.version());

            String name = change.name() == null ? promotion.name() : PromotionRules.name(change.name());
            int priority = change.priority() == null ? promotion.priority() : change.priority();
            PromotionStatus status = change.status() == null && change.active() == null
                    ? promotion.status()
                    : PromotionRules.status(change.status(), change.active());
            BigDecimal multiplier =
                    change.multiplier() == null ? promotion.multiplier() : PromotionRules.multiplier(change.multiplier());
            Instant[] window = PromotionRules.window(
                    change.startsAt() == null ? promotion.startsAt() : change.startsAt(),
                    change.endsAt() == null ? promotion.endsAt() : change.endsAt());
            PromotionConditions conditions = change.conditions() == null
                    ? promotion.conditions()
                    : PromotionRules.conditions(change.conditions());

            promotion.reconfigure(
                    name, priority, status, multiplier, window[0], window[1], conditions, clock.now());
            promotions.update(promotion);
            return promotion;
        });
    }
}
