package dev.ngb.email.sending.application.email.port;

import dev.ngb.email.sending.domain.email.EmailEvent;

/** The append-only event history. Nothing is ever updated or deleted here. */
public interface EmailEventRepository {

    void append(EmailEvent event);

    boolean exists(java.util.UUID emailId, dev.ngb.email.sending.domain.email.EmailEventType type);

    /** How many events of one type the email recorded since an instant; the public endpoints cap this. */
    long countSince(java.util.UUID emailId, dev.ngb.email.sending.domain.email.EmailEventType type, java.time.Instant since);
}
