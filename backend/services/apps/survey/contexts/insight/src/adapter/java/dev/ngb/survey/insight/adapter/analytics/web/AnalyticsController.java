package dev.ngb.survey.insight.adapter.analytics.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.collection.api.response.ResponseReader.Bucket;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.insight.application.analytics.query.AggregateView;
import dev.ngb.survey.insight.application.analytics.query.CrossTabQueries;
import dev.ngb.survey.insight.application.analytics.query.TrendView;
import dev.ngb.survey.insight.application.analytics.query.AggregateQueries;
import dev.ngb.survey.insight.application.analytics.query.TrendQueries;
import dev.ngb.survey.insight.application.funnel.query.FunnelView;
import dev.ngb.survey.insight.application.funnel.query.FunnelQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analytics a Tenant reads: per-question aggregates, trends, and the funnel.
 *
 * <p>Every body takes the same filter a response list does, so a Tenant narrows an aggregate exactly as it narrows the
 * list it drilled into from it.
 */
@Tag(
        name = "Survey analytics",
        description =
                "Per-question aggregates, cross-tabs, trends and the drop-off funnel.")
@RestController
@RequestMapping("/api/v1/surveys/{id}/analytics")
class AnalyticsController {

    private final AggregateQueries aggregates;
    private final CrossTabQueries crossTabs;
    private final TrendQueries trends;
    private final FunnelQueries funnels;

    AnalyticsController(
            AggregateQueries aggregates,
            CrossTabQueries crossTabs,
            TrendQueries trends,
            FunnelQueries funnels) {
        this.aggregates = aggregates;
        this.crossTabs = crossTabs;
        this.trends = trends;
        this.funnels = funnels;
    }

    @Operation(
            summary = "Aggregate a survey's answers",
            description =
                    "Returns per-question aggregates of the survey's responses: counts per option for a choice, the "
                    + "distribution of a number and the like. The filter is the one a response list takes: `version`, a "
                    + "`from`/`to` window, `source` and `externalRef`. Requires `survey:analytics:read`.")
    @PostMapping("/aggregate")
    @RequiresPermission(SurveyPermissions.ANALYTICS_READ)
    AggregateView aggregate(@PathVariable UUID id, @RequestBody FilterRequest request) {
        return aggregates.aggregate(id, request.filter());
    }

    @Operation(
            summary = "Cross-tabulate two questions",
            description =
                    "Counts how respondents answered `columnQuestionId` within each answer to `rowQuestionId`. The filter "
                    + "is the one a response list takes: `version`, a `from`/`to` window, `source` and `externalRef`. "
                    + "Requires `survey:analytics:read`.")
    @PostMapping("/crosstab")
    @RequiresPermission(SurveyPermissions.ANALYTICS_READ)
    AggregateView.CrossTabView crosstab(@PathVariable UUID id, @RequestBody CrossTabRequest request) {
        return crossTabs.crossTab(
                id, request.filter(), request.rowQuestionId(), request.columnQuestionId());
    }

    @Operation(
            summary = "Get a survey's response trend",
            description =
                    "Returns the number of responses per time `bucket` in `timezone`. The filter is the one a response "
                    + "list takes: `version`, a `from`/`to` window, `source` and `externalRef`. Requires "
                    + "`survey:analytics:read`.")
    @PostMapping("/trend")
    @RequiresPermission(SurveyPermissions.ANALYTICS_READ)
    TrendView trend(@PathVariable UUID id, @RequestBody TrendRequest request) {
        Bucket bucket = "week".equalsIgnoreCase(request.bucket()) ? Bucket.WEEK : Bucket.DAY;
        return trends.trend(id, request.filter(), bucket, request.timezone());
    }

    @Operation(
            summary = "Get a survey's drop-off funnel",
            description =
                    "Returns how many visits reached each page and how many completed, optionally split by `group_by`, "
                    + "within the `from`/`to` window. Requires `survey:analytics:read`.")
    @GetMapping("/funnel")
    @RequiresPermission(SurveyPermissions.ANALYTICS_READ)
    FunnelView funnel(
            @PathVariable UUID id,
            @RequestParam(name = "group_by", required = false) String groupBy,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return funnels.funnel(id, FunnelView.GroupBy.named(groupBy), from, to);
    }

    /**
     * A body that narrows a report.
     *
     * @param version the version number to include, or null for every one
     * @param from the earliest submit instant, or null
     * @param to the exclusive end of the window, or null
     * @param source one link source, or null
     * @param externalRef one recipient reference, or null
     */
    record FilterRequest(Integer version, Instant from, Instant to, String source, String externalRef) {

        ResponseFilter filter() {
            return new ResponseFilter(version, from, to, source, externalRef);
        }
    }

    /** A cross-tab request: the same filter, plus the two questions that form its rows and columns. */
    record CrossTabRequest(Integer version, Instant from, Instant to, String source, String externalRef,
            UUID rowQuestionId, UUID columnQuestionId) {

        ResponseFilter filter() {
            return new ResponseFilter(version, from, to, source, externalRef);
        }
    }

    /** A trend request: the same filter, plus the bucket and the survey's zone. */
    record TrendRequest(Integer version, Instant from, Instant to, String source, String externalRef,
            String bucket, String timezone) {

        ResponseFilter filter() {
            return new ResponseFilter(version, from, to, source, externalRef);
        }
    }
}
