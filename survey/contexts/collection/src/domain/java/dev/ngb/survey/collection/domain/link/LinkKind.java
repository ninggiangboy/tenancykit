package dev.ngb.survey.collection.domain.link;

/**
 * A link is public, one per Tenant-named source, or personal, one per recipient.
 *
 * <p>The kind is what makes a report meaningful: a public link's source is a campaign the Tenant names once, while a
 * personal link carries the recipient's own reference and may be single use.
 */
public enum LinkKind {
    /** One per source. Anyone holding the URL reaches the survey. */
    PUBLIC,
    /** One per recipient, with an external reference, an optional expiry and at most one completion. */
    PERSONAL
}
