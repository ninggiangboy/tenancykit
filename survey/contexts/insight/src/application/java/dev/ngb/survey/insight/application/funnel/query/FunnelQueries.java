package dev.ngb.survey.insight.application.funnel.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.VisitRow;
import dev.ngb.survey.insight.application.funnel.query.FunnelView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the funnel from visits: opened, started, completed, abandoned, grouped by the dimension the Tenant asked for.
 *
 * <p>Bot visits are dropped before counting, because a link preview is not a respondent. Opened and started are the
 * visit's own states; abandoned is what the sweep left behind, so a visit still in progress is not counted as
 * abandoned until the job moves it — which is why the funnel is a lagging count rather than a live one.
 */
public class FunnelQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public FunnelQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public FunnelView funnel(UUID surveyId, FunnelView.GroupBy groupBy, Instant from, Instant to) {
        return unitOfWork.transactional(() -> {
            List<VisitRow> visits = responses.visits(surveyId, from, to).stream()
                    .filter(visit -> !"BOT".equals(visit.trafficClass()))
                    .toList();

            Map<String, long[]> counts = new LinkedHashMap<>();
            long opened = 0;
            long started = 0;
            long completed = 0;
            long abandoned = 0;
            for (VisitRow visit : visits) {
                String key = groupKey(visit, groupBy);
                long[] row = counts.computeIfAbsent(key, ignored -> new long[4]);
                row[0]++;
                opened++;
                if (!"OPENED".equals(visit.state())) {
                    row[1]++;
                    started++;
                }
                if ("COMPLETED".equals(visit.state())) {
                    row[2]++;
                    completed++;
                }
                if ("ABANDONED".equals(visit.state())) {
                    row[3]++;
                    abandoned++;
                }
            }

            List<FunnelView.Row> rows = new ArrayList<>(counts.size());
            counts.forEach((key, row) -> rows.add(
                    new FunnelView.Row(key, row[0], row[1], row[2], row[3], null)));
            rows.sort(java.util.Comparator.comparingLong(FunnelView.Row::opened).reversed());
            return new FunnelView(
                    groupBy.name().toLowerCase(java.util.Locale.ROOT),
                    List.copyOf(rows),
                    opened,
                    started,
                    completed,
                    abandoned);
        });
    }

    private static String groupKey(VisitRow visit, FunnelView.GroupBy groupBy) {
        return switch (groupBy) {
            case SOURCE -> visit.source();
            case COUNTRY -> visit.country();
            case DEVICE_CLASS -> visit.deviceClass();
        };
    }
}
