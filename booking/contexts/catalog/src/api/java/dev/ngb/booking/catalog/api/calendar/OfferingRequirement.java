package dev.ngb.booking.catalog.api.calendar;

import java.util.UUID;

/** One requirement: a slot code and exactly one resource or group that fills it. */
public record OfferingRequirement(String slotCode, UUID resourceId, UUID groupId) {}
