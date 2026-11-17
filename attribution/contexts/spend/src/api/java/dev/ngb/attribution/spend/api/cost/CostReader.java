package dev.ngb.attribution.spend.api.cost;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The cost rows the insight context reads for its cost report.
 *
 * <p>A row is grouped by its source, so the report returns the per-source breakdown beside the total and a Tenant can
 * see a double entry (the same spend typed by hand and imported). This api is the only way insight sees spend.
 */
public interface CostReader {

    /**
     * The cost rows of one campaign or of every campaign, per day and source.
     *
     * @param fromDay the first day, inclusive
     * @param toDayExclusive the first day after the window
     * @param campaignId one campaign to narrow to, or null for every campaign
     */
    List<CostRow> costs(LocalDate fromDay, LocalDate toDayExclusive, UUID campaignId);

    /**
     * One source's cost for one campaign and day.
     *
     * @param amount the sum of that source's rows for the day; a day may hold several sources
     */
    record CostRow(UUID campaignId, LocalDate day, String source, BigDecimal amount, String currency) {}
}
