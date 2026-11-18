package dev.ngb.notification.dispatch.domain.notification;

/**
 * What happened to one notification's email.
 *
 * <p>{@code PUBLISHED} means "the command was handed to the outbox and is queued for mailer"; it never means
 * delivered. Nothing in this service knows what SES did, so mailer's ledger stays the only delivery truth.
 */
public enum EmailDispatchStatus {

    /** The row exists but the command has not been queued yet. */
    PENDING,

    /** The {@code email.send} command is in the outbox for mailer. */
    PUBLISHED,

    /** The user muted this type; the in-app row is still stored. */
    SUPPRESSED_MUTED,

    /** The user's account is disabled; the in-app row is still stored. */
    SKIPPED_INACTIVE,

    /** The replica has no usable address for the user. */
    SKIPPED_NO_ADDRESS,

    /** The outbox gave up publishing the command. */
    DEAD
}
