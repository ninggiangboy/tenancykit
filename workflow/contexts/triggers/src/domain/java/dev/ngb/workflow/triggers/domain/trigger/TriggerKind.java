package dev.ngb.workflow.triggers.domain.trigger;

/** What starts a workflow. */
public enum TriggerKind {
    /** A suite app's signed webhook. */
    APP,
    /** An external system's signed webhook. */
    EXTERNAL,
    /** A manual API call, which needs no stored trigger. */
    MANUAL,
    /** A cron schedule. */
    SCHEDULE;

    public boolean signed() {
        return this == APP || this == EXTERNAL;
    }
}
