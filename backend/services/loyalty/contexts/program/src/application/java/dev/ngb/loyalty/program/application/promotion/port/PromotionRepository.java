package dev.ngb.loyalty.program.application.promotion.port;

import dev.ngb.loyalty.program.domain.promotion.Promotion;
import dev.ngb.loyalty.program.domain.promotion.PromotionId;
import java.util.Optional;

/** The current tenant's promotions, as the command side writes them. */
public interface PromotionRepository {

    void add(Promotion promotion);

    void update(Promotion promotion);

    /** Reads a promotion under a row lock, so a patch cannot race another one. */
    Optional<Promotion> lockById(PromotionId id);

    /** How many promotions the tenant holds that are not archived, which the cap counts. */
    long countOpen();
}
