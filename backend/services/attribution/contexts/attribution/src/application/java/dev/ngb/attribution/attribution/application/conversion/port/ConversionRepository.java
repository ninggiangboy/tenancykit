package dev.ngb.attribution.attribution.application.conversion.port;

import dev.ngb.attribution.attribution.domain.credit.CreditEngine;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of conversions and their credits. */
public interface ConversionRepository {

    void insert(Conversion conversion);

    /** A conversion by its idempotency key, which is what makes a replayed postback return the first answer. */
    Optional<Conversion> findByIdempotencyKey(String idempotencyKey);

    Optional<Conversion> findById(UUID conversionId);

    /** Replace a conversion's chain, status and owning campaign, and its credits, in one transaction. */
    void settle(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits);

    /** Replace only the credits of an already-settled conversion, which is what a recompute does. */
    void replaceCredits(Conversion conversion, CreditEngine.Model model, List<CreditEngine.Credit> credits);

    /** The credits the current model produced, for a read that shows a conversion. */
    List<CreditEngine.Credit> credits(UUID conversionId);
}
