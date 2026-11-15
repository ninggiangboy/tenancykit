package dev.ngb.email.audience.domain.receiver;

/** Whether the customer currently consents to marketing mail. Transactional mail ignores this and honours only an ALL suppression. */
public enum ReceiverStatus {
    SUBSCRIBED,
    UNSUBSCRIBED
}
