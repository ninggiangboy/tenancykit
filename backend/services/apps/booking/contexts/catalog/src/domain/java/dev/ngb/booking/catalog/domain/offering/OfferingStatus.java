package dev.ngb.booking.catalog.domain.offering;

/** An offering's lifecycle: a draft is not sold, an active one is, an archived one is history. */
public enum OfferingStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
