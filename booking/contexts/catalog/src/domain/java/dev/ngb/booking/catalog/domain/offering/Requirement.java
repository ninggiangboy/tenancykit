package dev.ngb.booking.catalog.domain.offering;

import java.util.UUID;

/**
 * One named slot a booking must fill: the Tenant's slot code and either one resource or one group that may answer it.
 * Exactly one of the two is set, and a slot code is unique among an offering's requirements.
 */
public record Requirement(String slotCode, UUID resourceId, UUID groupId) {

    public Requirement {
        String code = slotCode == null ? null : slotCode.strip();
        if (code == null || code.isEmpty() || code.length() > 60) {
            throw new IllegalArgumentException("slotCode must be between 1 and 60 characters");
        }
        if ((resourceId == null) == (groupId == null)) {
            throw new IllegalArgumentException("a requirement names exactly one resource or group");
        }
        slotCode = code;
    }
}
