package dev.ngb.survey.collection.domain.visit;

/** Where a visit is in its life. The sweep moves an idle one to {@link #ABANDONED}; starting and completing are facts. */
public enum VisitState {
    /** The link was opened; no answer has been saved yet. */
    OPENED,
    /** The respondent saved at least one answer. */
    STARTED,
    /** The response was submitted. */
    COMPLETED,
    /** The sweep found it idle past the tenant's threshold. */
    ABANDONED
}
