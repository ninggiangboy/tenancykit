package dev.ngb.attribution.spend.adapter.cost.persistence;

import static dev.ngb.attribution_jooq.Tables.SPEND_ENTRIES;

import dev.ngb.attribution.spend.api.cost.CostReader;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The cost rows the insight cost report reads, grouped by day, campaign and source. */
public class JooqCostReader implements CostReader {

    private final DSLContext dsl;

    public JooqCostReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<CostRow> costs(LocalDate fromDay, LocalDate toDayExclusive, UUID campaignId) {
        var query = dsl.select(
                        SPEND_ENTRIES.CAMPAIGN_ID,
                        SPEND_ENTRIES.DAY,
                        SPEND_ENTRIES.SOURCE,
                        DSL.sum(SPEND_ENTRIES.AMOUNT).as("amount"),
                        DSL.max(SPEND_ENTRIES.CURRENCY).as("currency"))
                .from(SPEND_ENTRIES)
                .where(SPEND_ENTRIES.DAY.ge(fromDay).and(SPEND_ENTRIES.DAY.lt(toDayExclusive)));
        if (campaignId != null) {
            query = query.and(SPEND_ENTRIES.CAMPAIGN_ID.eq(campaignId));
        }
        return query.groupBy(SPEND_ENTRIES.CAMPAIGN_ID, SPEND_ENTRIES.DAY, SPEND_ENTRIES.SOURCE)
                .orderBy(SPEND_ENTRIES.DAY.asc(), SPEND_ENTRIES.CAMPAIGN_ID.asc(), SPEND_ENTRIES.SOURCE.asc())
                .fetch(row -> new CostRow(
                        row.get(SPEND_ENTRIES.CAMPAIGN_ID),
                        row.get(SPEND_ENTRIES.DAY),
                        row.get(SPEND_ENTRIES.SOURCE),
                        row.get("amount", java.math.BigDecimal.class),
                        row.get("currency", String.class)));
    }
}
