package dev.ngb.booking.inventory.adapter.capacity.persistence;

import dev.ngb.booking.inventory.api.capacity.PoolAvailability;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import java.util.UUID;

/** Serves the free-place count from the same read the capacity engine uses, so the two cannot disagree. */
public class JooqPoolAvailability implements PoolAvailability {

    private final CapacityUnitReader units;

    public JooqPoolAvailability(CapacityUnitReader units) {
        this.units = units;
    }

    @Override
    public int remaining(UUID sessionId, String poolCode) {
        return units.remaining(sessionId, poolCode);
    }
}
