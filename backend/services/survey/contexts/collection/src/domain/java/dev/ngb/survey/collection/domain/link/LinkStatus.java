package dev.ngb.survey.collection.domain.link;

/** Whether a link still opens the survey. Revoking is the only way to stop one that has been handed out. */
public enum LinkStatus {
    ACTIVE,
    REVOKED
}
