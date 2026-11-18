package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;

import dev.ngb.attribution.tracking.api.erasure.ConversionPersonEraser;
import org.jooq.DSLContext;

/**
 * Removes the person from a visitor's conversions while their amount and credits stay.
 *
 * <p>An erasure must not change revenue, so a conversion keeps its value, its currency and its credits; what it loses is
 * the visitor id and the click, which is the only part that names a person. The attribution context owns this table, so
 * this is where the tracking side's erasure reaches it, through the api the tracking context declares.
 */
public class JooqConversionPersonEraser implements ConversionPersonEraser {

    private final DSLContext dsl;

    public JooqConversionPersonEraser(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int erase(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            return 0;
        }
        return dsl.update(CONVERSIONS)
                .set(CONVERSIONS.VISITOR_ID, (String) null)
                .set(CONVERSIONS.CLICK_ID, (java.util.UUID) null)
                .where(CONVERSIONS.VISITOR_ID.eq(visitorId))
                .execute();
    }
}
