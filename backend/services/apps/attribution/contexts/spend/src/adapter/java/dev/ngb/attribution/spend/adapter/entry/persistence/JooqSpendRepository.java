package dev.ngb.attribution.spend.adapter.entry.persistence;

import static dev.ngb.attribution_jooq.Tables.SPEND_ENTRIES;

import dev.ngb.attribution.spend.application.entry.port.SpendRepository;
import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The cost store. A manual set replaces only the MANUAL row of that campaign, day and connection, so an imported row is
 * never touched; the same shape serves the import with its connection id.
 */
public class JooqSpendRepository implements SpendRepository {

    private final DSLContext dsl;

    public JooqSpendRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void upsert(SpendEntry entry) {
        dsl.deleteFrom(SPEND_ENTRIES).where(identity(entry)).execute();
        dsl.insertInto(SPEND_ENTRIES)
                .set(SPEND_ENTRIES.ID, entry.id())
                .set(SPEND_ENTRIES.TENANT_ID, entry.tenantId().value())
                .set(SPEND_ENTRIES.CAMPAIGN_ID, entry.campaignId())
                .set(SPEND_ENTRIES.DAY, entry.day())
                .set(SPEND_ENTRIES.SOURCE, entry.source().name())
                .set(SPEND_ENTRIES.CONNECTION_ID, entry.connectionId())
                .set(SPEND_ENTRIES.AMOUNT, entry.amount())
                .set(SPEND_ENTRIES.CURRENCY, entry.currency())
                .set(SPEND_ENTRIES.IMPRESSIONS, entry.impressions())
                .set(SPEND_ENTRIES.CLICKS, entry.clicks())
                .set(SPEND_ENTRIES.UPDATED_AT, entry.updatedAt())
                .execute();
    }

    @Override
    public void upsertAll(List<SpendEntry> entries) {
        for (SpendEntry entry : entries) {
            upsert(entry);
        }
    }

    @Override
    public int deleteManual(UUID campaignId, LocalDate day) {
        return dsl.deleteFrom(SPEND_ENTRIES)
                .where(SPEND_ENTRIES.CAMPAIGN_ID.eq(campaignId))
                .and(SPEND_ENTRIES.DAY.eq(day))
                .and(SPEND_ENTRIES.SOURCE.eq("MANUAL"))
                .and(SPEND_ENTRIES.CONNECTION_ID.isNull())
                .execute();
    }

    private static Condition identity(SpendEntry entry) {
        Condition match = SPEND_ENTRIES.CAMPAIGN_ID.eq(entry.campaignId())
                .and(SPEND_ENTRIES.DAY.eq(entry.day()))
                .and(SPEND_ENTRIES.SOURCE.eq(entry.source().name()));
        return entry.connectionId() == null
                ? match.and(SPEND_ENTRIES.CONNECTION_ID.isNull())
                : match.and(SPEND_ENTRIES.CONNECTION_ID.eq(entry.connectionId()));
    }
}
