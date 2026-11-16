package dev.ngb.survey.insight.application.analytics.query;

import dev.ngb.survey.collection.api.response.ResponseReader.BucketCount;
import java.util.List;

/**
 * A trend: the count of completed responses per day or week, in the survey's own time zone.
 *
 * @param bucket {@code day} or {@code week}
 * @param timezone the IANA zone the bucket is computed in
 * @param points one entry per non-empty bucket, in order
 */
public record TrendView(String bucket, String timezone, List<BucketCount> points) {}
