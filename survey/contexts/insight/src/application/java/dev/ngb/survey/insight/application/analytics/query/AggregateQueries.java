package dev.ngb.survey.insight.application.analytics.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.NpsValue;
import dev.ngb.survey.collection.api.response.ResponseReader.NumericValue;
import dev.ngb.survey.collection.api.response.ResponseReader.OptionCount;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.insight.application.analytics.query.AggregateView;
import dev.ngb.survey.insight.application.analytics.query.AggregateView.QuestionAggregate;
import dev.ngb.survey.insight.application.analytics.query.QuestionTypes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-question aggregates over a survey's completed responses, merged across versions by the question's stable id.
 *
 * <p>It reads the typed rows rather than the response JSONB, so the indexes on {@code answers} pay and a mean is an
 * average of a column. A choice reports counts per option value, a number its mean, median and distribution, an NPS
 * its promoter / passive / detractor split, and a matrix its per-cell counts.
 */
public class AggregateQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;
    private final QuestionTypes types;

    public AggregateQueries(UnitOfWork unitOfWork, ResponseReader responses, QuestionTypes types) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
        this.types = types;
    }

    public AggregateView aggregate(UUID surveyId, ResponseFilter filter) {
        return unitOfWork.transactional(() -> {
            long total = responses.count(surveyId, filter);
            Map<UUID, String> byId = types.of(surveyId);
            Map<UUID, Map<String, Long>> counts = new LinkedHashMap<>();
            for (OptionCount count : responses.optionCounts(surveyId, filter)) {
                counts.computeIfAbsent(count.questionId(), key -> new LinkedHashMap<>())
                        .merge(String.valueOf(count.optionId()), count.count(), Long::sum);
            }
            Map<UUID, List<BigDecimal>> numeric = new LinkedHashMap<>();
            for (NumericValue value : responses.numericValues(surveyId, filter)) {
                numeric.computeIfAbsent(value.questionId(), key -> new ArrayList<>()).add(value.value());
            }

            List<QuestionAggregate> questions = new ArrayList<>();
            byId.forEach((questionId, type) -> questions.add(aggregateOf(
                    questionId, type, counts.get(questionId), numeric.get(questionId), surveyId, filter)));
            return new AggregateView(surveyId, total, List.copyOf(questions), List.of());
        });
    }

    private QuestionAggregate aggregateOf(
            UUID questionId,
            String type,
            Map<String, Long> optionCounts,
            List<BigDecimal> values,
            UUID surveyId,
            ResponseFilter filter) {
        Map<String, Long> counts = optionCounts == null ? Map.of() : Map.copyOf(optionCounts);
        long answered = counts.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal mean = null;
        BigDecimal median = null;
        Map<String, Long> distribution = Map.of();

        if (values != null && !values.isEmpty()) {
            answered = Math.max(answered, values.size());
            mean = mean(values);
            median = median(values);
            distribution = switch (type) {
                case "nps" -> npsBuckets(values);
                default -> valueDistribution(values);
            };
        }
        Map<String, Long> cells = Map.of();
        if ("matrix".equals(type)) {
            Map<String, Long> cellCounts = new LinkedHashMap<>();
            responses.matrixCells(surveyId, filter, questionId)
                    .forEach(cell -> cellCounts.put(cell.rowId() + "|" + cell.columnId(), cell.count()));
            cells = Map.copyOf(cellCounts);
            answered = cellCounts.values().stream().mapToLong(Long::longValue).sum();
        }
        return new QuestionAggregate(questionId, type, answered, counts, mean, median, distribution, cells);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1)
                .add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_EVEN);
    }

    /** The counts per distinct numeric value, keyed by the value's plain decimal string. */
    private static Map<String, Long> valueDistribution(List<BigDecimal> values) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        values.forEach(value -> distribution.merge(value.stripTrailingZeros().toPlainString(), 1L, Long::sum));
        return Map.copyOf(distribution);
    }

    /** Promoters 9–10, passives 7–8, detractors 0–6, which is the split the score is computed from. */
    private static Map<String, Long> npsBuckets(List<BigDecimal> values) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("promoter", 0L);
        buckets.put("passive", 0L);
        buckets.put("detractor", 0L);
        for (NpsValue value : values.stream().map(NpsValue::new).toList()) {
            BigDecimal score = value.value();
            String bucket = score.compareTo(BigDecimal.valueOf(9)) >= 0
                    ? "promoter"
                    : score.compareTo(BigDecimal.valueOf(7)) >= 0 ? "passive" : "detractor";
            buckets.merge(bucket, 1L, Long::sum);
        }
        return Map.copyOf(buckets);
    }
}
