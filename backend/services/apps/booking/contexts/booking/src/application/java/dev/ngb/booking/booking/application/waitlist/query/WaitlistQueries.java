package dev.ngb.booking.booking.application.waitlist.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's waiting entries for the GET handlers. */
public class WaitlistQueries {

    private final UnitOfWork unitOfWork;
    private final WaitlistReadRepository entries;

    public WaitlistQueries(UnitOfWork unitOfWork, WaitlistReadRepository entries) {
        this.unitOfWork = unitOfWork;
        this.entries = entries;
    }

    public List<WaitlistView> list() {
        return unitOfWork.transactional(entries::list);
    }

    /** @throws NotFoundException when no entry of this tenant has the id */
    public WaitlistView get(UUID entryId) {
        return unitOfWork.transactional(() -> entries
                .findById(entryId)
                .orElseThrow(() -> new NotFoundException("No waitlist entry exists here with that id.")));
    }
}
