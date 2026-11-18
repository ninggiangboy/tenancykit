package dev.ngb.attribution.attribution.adapter.conversion.persistence;

import static dev.ngb.attribution_jooq.Tables.CONVERSIONS;
import static dev.ngb.attribution_jooq.Tables.CONVERSION_CREDITS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.attribution.attribution.application.conversion.query.ConversionListing;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionReadRepository;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionView;
import dev.ngb.attribution_jooq.tables.records.ConversionCreditsRecord;
import dev.ngb.attribution_jooq.tables.records.ConversionsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The conversion listing, shaped for its caller: the row, its snapshotted chain and its credits.
 *
 * <p>The listing compiles the caller's {@link ListQuery} against the columns {@link ConversionListing} whitelists, so a
 * window is a filter and a page continues from a cursor instead of stopping at a fixed row count. A page's credits are
 * read in one query keyed by the page's conversion ids rather than one query per conversion.
 */
public class JooqConversionReadRepository implements ConversionReadRepository {

    /** The conversion fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(ConversionListing.SCHEMA)
            .column("id", CONVERSIONS.ID)
            .column("goal_id", CONVERSIONS.GOAL_ID)
            .column("click_id", CONVERSIONS.CLICK_ID)
            .column("value", CONVERSIONS.VALUE)
            .column("currency", CONVERSIONS.CURRENCY)
            .column("occurred_at", CONVERSIONS.OCCURRED_AT)
            .column("source", CONVERSIONS.SOURCE)
            .column("credit_status", CONVERSIONS.CREDIT_STATUS)
            .column("owning_campaign_id", CONVERSIONS.OWNING_CAMPAIGN_ID)
            .column("computed_at", CONVERSIONS.COMPUTED_AT)
            .build();

    private final DSLContext dsl;

    public JooqConversionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** @return the binding of the conversion listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    @Override
    public Page<ConversionView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, CONVERSIONS, noCondition(), BINDING, query, this::toViews);
    }

    private List<ConversionView> toViews(List<ConversionsRecord> rows) {
        Map<UUID, List<ConversionView.CreditView>> byConversion =
                credits(rows.stream().map(ConversionsRecord::getId).toList());
        return rows.stream()
                .map(row -> new ConversionView(
                        row.getId(),
                        row.getGoalId(),
                        row.getValue(),
                        row.getCurrency(),
                        row.getOccurredAt(),
                        row.getSource(),
                        row.getCreditStatus(),
                        row.getOwningCampaignId(),
                        List.of(),
                        byConversion.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    /** @return every credit of {@code conversionIds}, grouped by conversion, in one query */
    private Map<UUID, List<ConversionView.CreditView>> credits(List<UUID> conversionIds) {
        if (conversionIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(CONVERSION_CREDITS)
                .where(CONVERSION_CREDITS.CONVERSION_ID.in(conversionIds))
                .fetchGroups(CONVERSION_CREDITS.CONVERSION_ID, JooqConversionReadRepository::toCredit);
    }

    private static ConversionView.CreditView toCredit(ConversionCreditsRecord row) {
        return new ConversionView.CreditView(row.getClickId().toString(), row.getCredit().toPlainString());
    }
}
