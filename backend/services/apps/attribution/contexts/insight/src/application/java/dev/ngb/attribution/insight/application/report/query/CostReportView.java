package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cost next to results: the money spent, the human clicks and the credited conversions, with the four ratios a Tenant
 * compares campaigns by.
 *
 * <p>A metric whose denominator is zero is null, never zero and never an error. {@code sources} lists every cost source
 * of the row, so the same spend typed by hand and imported is visible as two lines under one total.
 */
public record CostReportView(
        String groupBy,
        LocalDate from,
        LocalDate to,
        List<Row> rows,
        Instant dataThrough) {

    public CostReportView {
        rows = List.copyOf(rows);
    }

    /**
     * One grouped row.
     *
     * @param key the campaign id or the day, depending on {@code group_by}
     * @param excludedConversions conversions whose currency differs from the campaign's; they are kept out of the value
     *     metrics rather than added to unlike amounts
     */
    public record Row(
            String key,
            UUID campaignId,
            LocalDate day,
            BigDecimal cost,
            long humanClicks,
            BigDecimal conversions,
            BigDecimal creditedValue,
            BigDecimal cpc,
            BigDecimal cpa,
            BigDecimal roas,
            BigDecimal roi,
            BigDecimal excludedConversions,
            List<SourceCost> sources) {

        public Row {
            sources = List.copyOf(sources);
        }
    }

    /** One source's part of a row's cost. */
    public record SourceCost(String source, BigDecimal amount) {}
}
