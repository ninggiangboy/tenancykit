package dev.ngb.loyalty.program.application.promotion.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
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
 * Creates a promotion. A tenant may hold at most {@link PromotionRules#MAX_OPEN_PROMOTIONS} that are not archived, so a
 * program cannot grow a rule set nobody can read.
 */
public class CreatePromotionUseCase {

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
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreatePromotionUseCase(
            UnitOfWork unitOfWork,
            PromotionRepository promotions,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.promotions = promotions;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Promotion execute(Command command) {
        String name = PromotionRules.name(command.name());
        BigDecimal multiplier = PromotionRules.multiplier(command.multiplier());
        Instant[] window = PromotionRules.window(command.startsAt(), command.endsAt());
        PromotionStatus status = PromotionRules.status(command.status(), command.active());
        PromotionConditions conditions = PromotionRules.conditions(command.conditions());
        int priority = command.priority() == null ? 0 : command.priority();
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            if (promotions.countOpen() >= PromotionRules.MAX_OPEN_PROMOTIONS) {
                throw ProgramRefusal.invalid(
                        "this tenant already holds " + PromotionRules.MAX_OPEN_PROMOTIONS + " non-archived promotions");
            }
            Promotion created = Promotion.create(
                    tenantId,
                    new PromotionId(ids.newId()),
                    name,
                    priority,
                    status,
                    multiplier,
                    window[0],
                    window[1],
                    conditions,
                    clock.now());
            promotions.add(created);
            return created;
        });
    }
}
