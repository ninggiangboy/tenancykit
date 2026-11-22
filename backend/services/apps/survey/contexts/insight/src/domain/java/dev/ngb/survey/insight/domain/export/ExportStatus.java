package dev.ngb.survey.insight.domain.export;

/** Where an export is in its life. The worker moves a queued one to running, then to done or failed. */
public enum ExportStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
