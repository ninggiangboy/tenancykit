package dev.ngb.attribution.spend.adapter.entry.persistence;

import static dev.ngb.attribution_jooq.Tables.SPEND_ENTRIES;

import dev.ngb.attribution.spend.application.entry.query.SpendReadRepository;
import dev.ngb.attribution.spend.application.entry.query.SpendView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** The cost read side: every source's rows for a campaign in a day range. */
public class JooqSpendReadRepository implements SpendReadRepository {

    private final DSLContext dsl;

    public JooqSpendReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<SpendView> list(UUID campaignId, LocalDate fromDay, LocalDate toDayInclusive) {
        return dsl.selectFrom(SPEND_ENTRIES)
                .where(SPEND_ENTRIES.CAMPAIGN_ID.eq(campaignId))
                .and(SPEND_ENTRIES.DAY.ge(fromDay))
                .and(SPEND_ENTRIES.DAY.le(toDayInclusive))
                .orderBy(SPEND_ENTRIES.DAY.asc(), SPEND_ENTRIES.SOURCE.asc())
                .fetch(row -> new SpendView(
                        row.getId(),
                        row.getCampaignId(),
                        row.getDay(),
                        row.getSource(),
                        row.getConnectionId(),
                        row.getAmount(),
                        row.getCurrency(),
                        row.getImpressions(),
                        row.getClicks(),
                        row.getUpdatedAt()));
    }
}
