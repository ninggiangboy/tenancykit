package dev.ngb.mailroom.sending.domain.email;

/**
 * The sent-email state machine: {@code QUEUED} until mailer reports an outcome. {@code SKIPPED} (an eligibility
 * refusal) and {@code FAILED} (a render or validation refusal) never reach the broker.
 */
public enum EmailStatus {
    QUEUED,
    SENT,
    FAILED,
    SKIPPED
}
