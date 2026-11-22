package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** A half-open instant window, with the UTC days it covers for the daily rollups. */
public record ReportRange(Instant from, Instant to) {

    public ReportRange {
        if (from == null || to == null) {
            throw new BadRequestException("a report needs a from and a to instant");
        }
        if (!from.isBefore(to)) {
            throw new BadRequestException("a report's to must be after its from");
        }
    }

    public LocalDate fromDay() {
        return from.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public LocalDate toDayExclusive() {
        return to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
    }

    /** The 30 days ending at {@code now}, which is what the link statistics endpoint shows. */
    public static ReportRange last30Days(Instant now) {
        return new ReportRange(now.minus(30, java.time.temporal.ChronoUnit.DAYS), now);
    }
}
