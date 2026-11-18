package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.util.Set;

/** One series request: its window, bucket, metrics, filters and traffic classes. */
public record ReportQuery(
        ReportRange range, Granularity granularity, Set<Metric> metrics, ReportFilter filter,
        TrafficClassFilter traffic) {

    public ReportQuery {
        metrics = Set.copyOf(metrics);
        if (metrics.isEmpty()) {
            throw new BadRequestException("a report needs at least one metric");
        }
        if (granularity == Granularity.HOUR && metrics.stream().anyMatch(Metric::conversionMetric)) {
            throw new BadRequestException("conversion metrics are daily only");
        }
    }
}
