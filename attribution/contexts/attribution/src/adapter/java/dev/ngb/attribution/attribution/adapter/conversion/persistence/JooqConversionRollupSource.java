package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;
import static dev.ngb.attribution_jooq.Tables.CONVERSION_CREDITS;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The credit aggregates behind the conversion rollup and the cohort, read from the conversion and credit tables.
 *
 * <p>A credit row names the campaign and link that received it, so the rollup groups by those rather than by the owning
 * campaign: one conversion may credit several campaigns across its chain, and the report is about where the value
 * landed. The cohort instead groups by the owning campaign, because a cohort is about the campaign that governs the
 * conversion.
 */
public class JooqConversionRollupSource implements ConversionRollupSource {

    private static final int SECONDS_PER_WEEK = 604_800;

    private final DSLContext dsl;

    public JooqConversionRollupSource(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ConversionBucket> buckets(LocalDate fromDay, LocalDate toDayExclusive) {
        Field<LocalDate> day = DSL.field(
                "({0} at time zone 'UTC')::date", LocalDate.class, CONVERSIONS.OCCURRED_AT);
        Field<Integer> chainLength = DSL.field("jsonb_array_length({0})", Integer.class, CONVERSIONS.TOUCH_CHAIN);
        // A conversion is counted as the sum of the credit weights it gave this campaign, so a chain that splits across
        // campaigns contributes its share and a linear conversion stays exactly one in total.
        Field<BigDecimal> weighted = CONVERSION_CREDITS.WEIGHT;
        Field<BigDecimal> multiTouch = DSL.sum(
                        DSL.when(chainLength.gt(1), weighted).otherwise(BigDecimal.ZERO))
                .as("multi_touch");
        return dsl.select(
                        day.as("day"),
                        CONVERSION_CREDITS.CAMPAIGN_ID,
                        CONVERSION_CREDITS.LINK_ID,
                        CONVERSIONS.GOAL_ID,
                        CONVERSION_CREDITS.MODEL,
                        CONVERSIONS.CURRENCY,
                        DSL.sum(weighted).as("conversions"),
                        DSL.sum(CONVERSION_CREDITS.CREDIT).as("credited_value"),
                        multiTouch)
                .from(CONVERSIONS)
                .join(CONVERSION_CREDITS)
                .on(CONVERSION_CREDITS.TENANT_ID.eq(CONVERSIONS.TENANT_ID)
                        .and(CONVERSION_CREDITS.CONVERSION_ID.eq(CONVERSIONS.ID)))
                .where(CONVERSIONS.OCCURRED_AT.ge(startOf(fromDay))
                        .and(CONVERSIONS.OCCURRED_AT.lt(startOf(toDayExclusive))))
                .groupBy(day, CONVERSION_CREDITS.CAMPAIGN_ID, CONVERSION_CREDITS.LINK_ID, CONVERSIONS.GOAL_ID,
                        CONVERSION_CREDITS.MODEL, CONVERSIONS.CURRENCY)
                .fetch(row -> new ConversionBucket(
                        row.get("day", LocalDate.class),
                        row.get(CONVERSION_CREDITS.CAMPAIGN_ID),
                        row.get(CONVERSION_CREDITS.LINK_ID),
                        row.get(CONVERSIONS.GOAL_ID),
                        row.get(CONVERSION_CREDITS.MODEL),
                        row.get(CONVERSIONS.CURRENCY),
                        row.get("conversions", BigDecimal.class),
                        row.get("credited_value", BigDecimal.class),
                        row.get("multi_touch", BigDecimal.class)));
    }

    @Override
    public List<CohortCell> cohorts(UUID campaignId, LocalDate fromDay, LocalDate toDayExclusive, int periods) {
        Field<OffsetDateTime> firstClick = DSL.field(
                "({0}->0->>'at')::timestamptz", OffsetDateTime.class, CONVERSIONS.TOUCH_CHAIN);
        Field<LocalDate> cohortWeek = DSL.field(
                "date_trunc('week', ({0} at time zone 'UTC'))::date", LocalDate.class, firstClick);
        Field<Integer> week = DSL.field(
                "floor(extract(epoch from ({0} - {1})) / " + SECONDS_PER_WEEK + ")::int",
                Integer.class, CONVERSIONS.OCCURRED_AT, firstClick);
        Field<Integer> chainLength = DSL.field("jsonb_array_length({0})", Integer.class, CONVERSIONS.TOUCH_CHAIN);
        return dsl.select(
                        cohortWeek.as("cohort_week"),
                        week.as("week"),
                        DSL.countDistinct(CONVERSIONS.ID).as("conversions"),
                        DSL.sum(CONVERSION_CREDITS.CREDIT).as("value"))
                .from(CONVERSIONS)
                .join(CONVERSION_CREDITS)
                .on(CONVERSION_CREDITS.TENANT_ID.eq(CONVERSIONS.TENANT_ID)
                        .and(CONVERSION_CREDITS.CONVERSION_ID.eq(CONVERSIONS.ID)))
                .where(CONVERSIONS.OWNING_CAMPAIGN_ID.eq(campaignId))
                .and(CONVERSIONS.OCCURRED_AT.ge(startOf(fromDay)))
                .and(CONVERSIONS.OCCURRED_AT.lt(startOf(toDayExclusive)))
                .and(chainLength.gt(0))
                .groupBy(cohortWeek, week)
                .having(week.le(periods))
                .orderBy(cohortWeek.asc(), week.asc())
                .fetch(row -> new CohortCell(
                        row.get("cohort_week", LocalDate.class),
                        row.get("week", Integer.class),
                        row.get("conversions", Long.class),
                        row.get("value", BigDecimal.class)));
    }

    @Override
    public long convertedClicks(UUID campaignId, UUID linkId, UUID goalId, Instant from, Instant to) {
        Condition where = CONVERSIONS.OCCURRED_AT.ge(from).and(CONVERSIONS.OCCURRED_AT.lt(to));
        if (campaignId != null) {
            where = where.and(CONVERSION_CREDITS.CAMPAIGN_ID.eq(campaignId));
        }
        if (linkId != null) {
            where = where.and(CONVERSION_CREDITS.LINK_ID.eq(linkId));
        }
        if (goalId != null) {
            where = where.and(CONVERSIONS.GOAL_ID.eq(goalId));
        }
        Long converted = dsl.select(DSL.countDistinct(CONVERSION_CREDITS.CLICK_ID))
                .from(CONVERSIONS)
                .join(CONVERSION_CREDITS)
                .on(CONVERSION_CREDITS.TENANT_ID.eq(CONVERSIONS.TENANT_ID)
                        .and(CONVERSION_CREDITS.CONVERSION_ID.eq(CONVERSIONS.ID)))
                .where(where)
                .fetchOne(0, Long.class);
        return converted == null ? 0 : converted;
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
