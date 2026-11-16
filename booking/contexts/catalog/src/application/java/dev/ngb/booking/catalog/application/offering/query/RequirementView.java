package dev.ngb.booking.catalog.application.offering.query;

import java.util.UUID;

/** One requirement of an offering as a read returns it: a slot code and one resource or one group. */
public record RequirementView(String slotCode, UUID resourceId, UUID groupId) {}
