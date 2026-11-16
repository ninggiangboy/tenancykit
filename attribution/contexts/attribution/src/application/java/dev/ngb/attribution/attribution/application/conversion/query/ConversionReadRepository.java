package dev.ngb.attribution.attribution.application.conversion.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** The conversion read side: one page of the conversions the filter admits, for the API. */
public interface ConversionReadRepository {

    Page<ConversionView> list(ListQuery query);
}
