package dev.ngb.loyalty.program.api.program;

import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.api.tier.TierView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What another context may read of the program, and the only door onto its three tables.
 *
 * <p>Every answer is immutable and tenant-scoped by the caller's request scope. A tenant with no program configured gets
 * an empty program and no tiers or promotions, which is exactly the state in which nothing earns.
 */
public interface ProgramReader {

    /** The Tenant's one program, or empty until the first {@code PUT /program}. */
    Optional<ProgramView> program();

    /** The whole tier list, lowest first. */
    List<TierView> tiers();

    /**
     * The {@link PromotionStatus#ACTIVE} promotions whose window contains {@code instant}, ordered by priority from the
     * highest, each stamped with the program's zone so its weekday and time-of-day rules can be applied.
     */
    List<PromotionView> activePromotionsAt(Instant instant);
}
