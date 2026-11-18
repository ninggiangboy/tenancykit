package dev.ngb.attribution.insight.application.report.service;

import dev.ngb.attribution.insight.application.report.query.BreakdownView;
import dev.ngb.attribution.insight.application.report.query.CohortView;
import dev.ngb.attribution.insight.application.report.query.CostReportView;
import dev.ngb.attribution.insight.application.report.query.FunnelView;
import dev.ngb.attribution.insight.application.report.query.LinkStatsView;
import dev.ngb.attribution.insight.application.report.query.TimeSeriesView;
import dev.ngb.attribution.insight.domain.export.CsvWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV form of each report, built from the same view the JSON answer carries.
 *
 * <p>That is what makes an export equal its JSON: the file is written from the object the endpoint would serialise, not
 * from a second query. A missing metric is an empty cell, and every numeric is written with its exact decimal scale.
 */
public final class CsvReportWriter {

    private CsvReportWriter() {}

    public static List<String> rows(TimeSeriesView view) {
        List<String> lines = new ArrayList<>(view.points().size() + 1);
        lines.add(CsvWriter.row(List.of(
                "bucket", "clicks", "page_views", "sessions", "unique_visitor_days", "conversions",
                "credited_value", "multi_touch_share")));
        for (TimeSeriesView.Point point : view.points()) {
            lines.add(CsvWriter.row(List.of(
                    text(point.bucket()),
                    text(point.clicks()),
                    text(point.pageViews()),
                    text(point.sessions()),
                    text(point.uniqueVisitorDays()),
                    text(point.conversions()),
                    text(point.creditedValue()),
                    text(point.multiTouchShare()))));
        }
        return lines;
    }

    public static List<String> rows(BreakdownView view) {
        List<String> lines = new ArrayList<>(view.rows().size() + 1);
        lines.add(CsvWriter.row(List.of(
                view.dimension(), "clicks", "page_views", "sessions", "unique_visitor_days", "conversions",
                "credited_value", "multi_touch_share")));
        for (BreakdownView.Row row : view.rows()) {
            lines.add(CsvWriter.row(List.of(
                    text(row.key()),
                    text(row.clicks()),
                    text(row.pageViews()),
                    text(row.sessions()),
                    text(row.uniqueVisitorDays()),
                    text(row.conversions()),
                    text(row.creditedValue()),
                    text(row.multiTouchShare()))));
        }
        return lines;
    }

    public static List<String> rows(FunnelView view) {
        List<String> lines = new ArrayList<>(2);
        lines.add(CsvWriter.row(List.of(
                view.entity() + "_id", "clicks", "landed", "converted", "landed_rate", "converted_rate")));
        lines.add(CsvWriter.row(List.of(
                text(view.entityId()),
                text(view.clicks()),
                text(view.landed()),
                text(view.converted()),
                text(view.landedRate()),
                text(view.convertedRate()))));
        return lines;
    }

    public static List<String> rows(CohortView view) {
        List<String> lines = new ArrayList<>();
        lines.add(CsvWriter.row(List.of("cohort_week", "week", "conversions", "value")));
        for (CohortView.Row row : view.rows()) {
            for (CohortView.Cell cell : row.cells()) {
                lines.add(CsvWriter.row(List.of(
                        text(row.cohortWeek()), text(cell.week()), text(cell.conversions()), text(cell.value()))));
            }
        }
        return lines;
    }

    public static List<String> rows(CostReportView view) {
        List<String> lines = new ArrayList<>(view.rows().size() + 1);
        lines.add(CsvWriter.row(List.of(
                view.groupBy(), "cost", "human_clicks", "conversions", "credited_value", "cpc", "cpa", "roas", "roi",
                "excluded_conversions", "sources")));
        for (CostReportView.Row row : view.rows()) {
            String sources = row.sources().stream()
                    .map(source -> source.source() + "=" + text(source.amount()))
                    .reduce((left, right) -> left + ";" + right)
                    .orElse("");
            lines.add(CsvWriter.row(List.of(
                    text(row.key()),
                    text(row.cost()),
                    text(row.humanClicks()),
                    text(row.conversions()),
                    text(row.creditedValue()),
                    text(row.cpc()),
                    text(row.cpa()),
                    text(row.roas()),
                    text(row.roi()),
                    text(row.excludedConversions()),
                    sources)));
        }
        return lines;
    }

    public static List<String> rows(LinkStatsView view) {
        List<String> lines = new ArrayList<>(view.last30Days().size() + 1);
        lines.add(CsvWriter.row(List.of("day", "clicks")));
        for (LinkStatsView.DailyCount day : view.last30Days()) {
            lines.add(CsvWriter.row(List.of(text(day.day()), text(day.clicks()))));
        }
        return lines;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }
}
