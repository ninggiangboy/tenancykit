package dev.ngb.workflow.runtime.domain.timer;

/** Why a timer exists. A delay resumes its own node; a timeout resumes the wait on its timeout branch. */
public enum TimerKind {
    DELAY,
    WAIT_TIMEOUT,
    TASK_DUE,
    RETRY
}
