package dev.ngb.booking.booking.application.waitlist.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads waiting entries straight into their views; the aggregate is never rebuilt for a read. */
public interface WaitlistReadRepository {

    List<WaitlistView> list();

    Optional<WaitlistView> findById(UUID entryId);
}
