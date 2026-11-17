package dev.ngb.attribution.insight.adapter.report.web;

import dev.ngb.attribution.insight.application.report.query.BreakdownView;
import dev.ngb.attribution.insight.application.report.query.CohortQuery;
import dev.ngb.attribution.insight.application.report.query.CohortView;
import dev.ngb.attribution.insight.application.report.query.CostReportView;
import dev.ngb.attribution.insight.application.report.query.Dimension;
import dev.ngb.attribution.insight.application.report.query.FunnelQuery;
import dev.ngb.attribution.insight.application.report.query.FunnelView;
import dev.ngb.attribution.insight.application.report.query.Granularity;
import dev.ngb.attribution.insight.application.report.query.Metric;
import dev.ngb.attribution.insight.application.report.query.ReportFilter;
import dev.ngb.attribution.insight.application.report.query.ReportQueries;
import dev.ngb.attribution.insight.application.report.query.ReportQuery;
import dev.ngb.attribution.insight.application.report.query.ReportRange;
import dev.ngb.attribution.insight.application.report.query.TimeSeriesView;
import dev.ngb.attribution.insight.application.report.query.TrafficClassFilter;
import dev.ngb.attribution.insight.application.report.service.CsvReportWriter;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The reports a Tenant reads: a time series, a breakdown, the funnel, cohorts and the cost report.
 *
 * <p>Every report answers {@code text/csv} when the request asks for it by {@code Accept} or by the {@code .csv} path,
 * and the file is written from the same view the JSON answer would serialise. An export is capped at 100,000 rows; a
 * larger answer is a 400 that asks for a narrower range.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private static final String CSV = "text/csv";
    private static final int MAX_EXPORT_ROWS = 100_000;

    private final ReportQueries reports;
    private final Clock clock;

    ReportController(ReportQueries reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @GetMapping(value = "/timeseries", produces = {MediaType.APPLICATION_JSON_VALUE, CSV})
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<?> timeseries(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false) String include,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "destination_id", required = false) UUID destinationId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String entry,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String country,
            HttpServletRequest request) {
        TimeSeriesView view = reports.timeseries(new ReportQuery(
                range(from, to), Granularity.named(granularity), Metric.parse(metrics),
                filter(linkId, campaignId, destinationId, goalId, entry, device, country, null),
                TrafficClassFilter.of(include)));
        return wantsCsv(request) ? csv("timeseries.csv", CsvReportWriter.rows(view)) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/timeseries.csv", produces = CSV)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<StreamingResponseBody> timeseriesCsv(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false) String include,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "destination_id", required = false) UUID destinationId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String entry,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String country) {
        TimeSeriesView view = reports.timeseries(new ReportQuery(
                range(from, to), Granularity.named(granularity), Metric.parse(metrics),
                filter(linkId, campaignId, destinationId, goalId, entry, device, country, null),
                TrafficClassFilter.of(include)));
        return csv("timeseries.csv", CsvReportWriter.rows(view));
    }

    @GetMapping(value = "/breakdown", produces = {MediaType.APPLICATION_JSON_VALUE, CSV})
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<?> breakdown(
            @RequestParam String dimension,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false) String include,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "destination_id", required = false) UUID destinationId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String entry,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String referrer,
            HttpServletRequest request) {
        BreakdownView view = reports.breakdown(new ReportQuery(
                range(from, to), Granularity.named(granularity), Metric.parse(metrics),
                filter(linkId, campaignId, destinationId, goalId, entry, device, country, referrer),
                TrafficClassFilter.of(include)), Dimension.named(dimension));
        return wantsCsv(request) ? csv("breakdown.csv", CsvReportWriter.rows(view)) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/breakdown.csv", produces = CSV)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<StreamingResponseBody> breakdownCsv(
            @RequestParam String dimension,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false) String include,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "destination_id", required = false) UUID destinationId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String entry,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String referrer) {
        BreakdownView view = reports.breakdown(new ReportQuery(
                range(from, to), Granularity.named(granularity), Metric.parse(metrics),
                filter(linkId, campaignId, destinationId, goalId, entry, device, country, referrer),
                TrafficClassFilter.of(include)), Dimension.named(dimension));
        return csv("breakdown.csv", CsvReportWriter.rows(view));
    }

    @GetMapping(value = "/funnel", produces = {MediaType.APPLICATION_JSON_VALUE, CSV})
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<?> funnel(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String include,
            HttpServletRequest request) {
        FunnelView view = reports.funnel(funnelQuery(from, to, campaignId, linkId, goalId, include));
        return wantsCsv(request) ? csv("funnel.csv", CsvReportWriter.rows(view)) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/funnel.csv", produces = CSV)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<StreamingResponseBody> funnelCsv(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "link_id", required = false) UUID linkId,
            @RequestParam(name = "goal_id", required = false) UUID goalId,
            @RequestParam(required = false) String include) {
        return csv("funnel.csv", CsvReportWriter.rows(
                reports.funnel(funnelQuery(from, to, campaignId, linkId, goalId, include))));
    }

    @GetMapping(value = "/cohorts", produces = {MediaType.APPLICATION_JSON_VALUE, CSV})
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<?> cohorts(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false, defaultValue = "12") int periods,
            HttpServletRequest request) {
        CohortView view = reports.cohorts(cohortQuery(from, to, campaignId, cohort, periods));
        return wantsCsv(request) ? csv("cohorts.csv", CsvReportWriter.rows(view)) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/cohorts.csv", produces = CSV)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<StreamingResponseBody> cohortsCsv(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false, defaultValue = "12") int periods) {
        return csv("cohorts.csv", CsvReportWriter.rows(
                reports.cohorts(cohortQuery(from, to, campaignId, cohort, periods))));
    }

    @GetMapping(value = "/costs", produces = {MediaType.APPLICATION_JSON_VALUE, CSV})
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<?> costs(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "group_by", required = false) String groupBy,
            HttpServletRequest request) {
        CostReportView view = reports.costs(range(from, to), campaignId, groupBy);
        return wantsCsv(request) ? csv("costs.csv", CsvReportWriter.rows(view)) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/costs.csv", produces = CSV)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ResponseEntity<StreamingResponseBody> costsCsv(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "campaign_id", required = false) UUID campaignId,
            @RequestParam(name = "group_by", required = false) String groupBy) {
        return csv("costs.csv", CsvReportWriter.rows(reports.costs(range(from, to), campaignId, groupBy)));
    }

    private FunnelQuery funnelQuery(
            Instant from, Instant to, UUID campaignId, UUID linkId, UUID goalId, String include) {
        Instant now = clock.now();
        return new FunnelQuery(
                from == null ? now.minus(30, ChronoUnit.DAYS) : from,
                to == null ? now : to,
                campaignId, linkId, goalId, TrafficClassFilter.of(include));
    }

    private CohortQuery cohortQuery(Instant from, Instant to, UUID campaignId, String cohort, int periods) {
        if (cohort != null && !cohort.isBlank() && !cohort.strip().equalsIgnoreCase("week")) {
            throw new dev.ngb.core.error.BadRequestException("cohort is week in v1");
        }
        Instant now = clock.now();
        return new CohortQuery(
                campaignId,
                from == null ? now.minus(84, ChronoUnit.DAYS) : from,
                to == null ? now : to,
                periods);
    }

    private ReportRange range(Instant from, Instant to) {
        Instant now = clock.now();
        return new ReportRange(
                from == null ? now.minus(30, ChronoUnit.DAYS) : from,
                to == null ? now : to);
    }

    private static ReportFilter filter(
            UUID linkId, UUID campaignId, UUID destinationId, UUID goalId, String entry, String device, String country,
            String referrer) {
        return new ReportFilter(linkId, campaignId, destinationId, goalId, entry, device, country, referrer);
    }

    private static boolean wantsCsv(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(CSV);
    }

    private static ResponseEntity<StreamingResponseBody> csv(String name, List<String> lines) {
        // The first line is the header; the cap is on data rows.
        if (lines.size() - 1 > MAX_EXPORT_ROWS) {
            throw new dev.ngb.core.error.BadRequestException(
                    "An export holds at most " + MAX_EXPORT_ROWS + " rows; ask for a narrower range.");
        }
        StreamingResponseBody body = output -> {
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    writer.write(line);
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(CSV))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }
}
