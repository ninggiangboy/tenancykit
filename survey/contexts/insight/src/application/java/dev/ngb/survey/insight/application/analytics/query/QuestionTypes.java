package dev.ngb.survey.insight.application.analytics.query;

import java.util.Map;
import java.util.UUID;

/**
 * The type of every question a survey's versions carry, keyed by the stable question id.
 *
 * <p>An aggregate needs it because the typed rows say where a value is stored but not what it means: a `num_value` is a
 * rating in one question and an NPS in another, and the two are bucketed differently.
 */
public interface QuestionTypes {

    /** Every question id a survey's published version carries and its type. */
    Map<UUID, String> of(UUID surveyId);
}
