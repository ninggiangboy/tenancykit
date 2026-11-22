package dev.ngb.mailroom.sending.domain.email;

/** What may be appended to an email's append-only event history. */
public enum EmailEventType {
    SENT,
    FAILED,
    OPENED,
    CLICKED,
    UNSUBSCRIBED
}
