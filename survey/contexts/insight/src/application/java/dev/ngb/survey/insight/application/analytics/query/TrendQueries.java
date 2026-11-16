package dev.ngb.survey.insight.application.analytics.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.insight.application.analytics.query.QuestionTypes;
import dev.ngb.survey.insight.application.analytics.query.TrendView;
import java.util.List;
import java.util.UUID;

/**
 * A trend: the count or a mean over the responses of each day or week, in the survey's own time zone.
 *
 * <p>The bucket is the Tenant's day, not UTC's, because a survey closing at midnight in Asia/Ho_Chi_Minh would otherwise
 * split one day into two.
 */
public class TrendQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;
    private final QuestionTypes types;

    public TrendQueries(UnitOfWork unitOfWork, ResponseReader responses, QuestionTypes types) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
        this.types = types;
    }

    /**
     * @param bucket day or week
     * @param timezone the survey's IANA zone, or null for UTC
     */
    public TrendView trend(
            UUID surveyId, ResponseFilter filter, ResponseReader.Bucket bucket, String timezone) {
        return unitOfWork.transactional(() -> new TrendView(
                bucket.name().toLowerCase(java.util.Locale.ROOT),
                timezone == null ? "UTC" : timezone,
                List.copyOf(responses.submittedByBucket(surveyId, filter, bucket, timezone))));
    }
}
