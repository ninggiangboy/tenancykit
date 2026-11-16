package dev.ngb.attribution.attribution.application.conversion.query;

import java.time.Instant;
import java.util.List;

/** The conversion read side: the conversions in a window, for the API. */
public interface ConversionReadRepository {

    List<ConversionView> list(Instant from, Instant to);
}
