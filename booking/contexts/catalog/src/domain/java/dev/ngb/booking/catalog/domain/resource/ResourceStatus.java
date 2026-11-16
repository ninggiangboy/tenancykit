package dev.ngb.booking.catalog.domain.resource;

/** Whether a resource may still be booked: an archived one keeps its history and takes nothing new. */
public enum ResourceStatus {
    ACTIVE,
    ARCHIVED
}
