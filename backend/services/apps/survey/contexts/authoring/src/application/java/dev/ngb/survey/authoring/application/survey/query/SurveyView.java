package dev.ngb.survey.authoring.application.survey.query;

import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import java.util.UUID;

/**
 * One survey as the API returns it: the pointer to its current version, that version's number and the optimistic-lock
 * version the survey endpoints answer as an ETag.
 */
public record SurveyView(
        UUID id, String name, SurveyStatus status, UUID currentVersionId, Integer currentVersionNo, long version) {

    public SurveyView {
        if (currentVersionNo != null && currentVersionNo < 1) {
            throw new IllegalArgumentException("a version is numbered from 1");
        }
    }
}
