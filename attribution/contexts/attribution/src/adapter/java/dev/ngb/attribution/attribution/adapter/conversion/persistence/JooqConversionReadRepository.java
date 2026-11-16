package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;
import static dev.ngb.attribution_jooq.Tables.CONVERSION_CREDITS;

import dev.ngb.attribution.attribution.application.conversion.query.ConversionReadRepository;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionView;
import java.time.Instant;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The conversion listing, shaped for its caller: the row, its snapshotted chain and its credits. */
public class JooqConversionReadRepository implements ConversionReadRepository {

    private final DSLContext dsl;

    public JooqConversionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ConversionView> list(Instant from, Instant to) {
        Condition where = DSL.noCondition();
        if (from != null) {
            where = where.and(CONVERSIONS.OCCURRED_AT.ge(from));
        }
        if (to != null) {
            where = where.and(CONVERSIONS.OCCURRED_AT.le(to));
        }
        return dsl.selectFrom(CONVERSIONS)
                .where(where)
                .orderBy(CONVERSIONS.OCCURRED_AT.desc())
                .limit(500)
                .fetch(row -> new ConversionView(
                        row.getId(),
                        row.getGoalId(),
                        row.getValue(),
                        row.getCurrency(),
                        row.getOccurredAt(),
                        row.getSource(),
                        row.getCreditStatus(),
                        row.getOwningCampaignId(),
                        List.of(),
                        credits(row.getId())));
    }

    private List<ConversionView.CreditView> credits(java.util.UUID conversionId) {
        return dsl.selectFrom(CONVERSION_CREDITS)
                .where(CONVERSION_CREDITS.CONVERSION_ID.eq(conversionId))
                .fetch(row -> new ConversionView.CreditView(
                        row.getClickId().toString(), row.getCredit().toPlainString()));
    }
}
