package dev.ngb.attribution.attribution.application.conversion.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.List;

/** The conversion read side, opened in a unit of work so row-level security scopes it to the caller's tenant. */
public class ConversionQueries {

    private final UnitOfWork unitOfWork;
    private final ConversionReadRepository conversions;

    public ConversionQueries(UnitOfWork unitOfWork, ConversionReadRepository conversions) {
        this.unitOfWork = unitOfWork;
        this.conversions = conversions;
    }

    public List<ConversionView> list(Instant from, Instant to) {
        return unitOfWork.transactional(() -> conversions.list(from, to));
    }
}
