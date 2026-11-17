package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A cohort: the UTC week of the first click in a conversion's chain, and the weeks since it that produced credit.
 *
 * @param cohort the cohort unit, {@code week} in v1
 */
public record CohortView(
        UUID campaignId, String cohort, List<Row> rows, Instant dataThrough) {

    public CohortView {
        rows = List.copyOf(rows);
    }

    /** One cohort week's cells, plus its totals. */
    public record Row(String cohortWeek, List<Cell> cells, long conversions, BigDecimal value) {

        public Row {
            cells = List.copyOf(cells);
        }
    }

    /** One week-since-click column. */
    public record Cell(int week, long conversions, BigDecimal value) {}
}
