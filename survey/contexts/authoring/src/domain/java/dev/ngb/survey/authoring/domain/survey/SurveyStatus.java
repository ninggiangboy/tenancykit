package dev.ngb.survey.authoring.domain.survey;

/** The lifecycle of a survey. A published survey is open until the Tenant closes it. */
public enum SurveyStatus {
    /** No version has been published yet; the draft is the only content. */
    DRAFT,
    /** The current version accepts responses. */
    OPEN,
    /** No version accepts responses; links answer 404. */
    CLOSED
}
