package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.dispatch.domain.notification.EmailDispatch;

/** The ledger; the unique key makes a repeated transition a no-op rather than a second email. */
public interface EmailDispatchRepository {

    /** @return true when inserted, false when this transition already has a dispatch row */
    boolean insertIfAbsent(EmailDispatch dispatch);
}
