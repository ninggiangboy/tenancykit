package dev.ngb.attribution.links.domain.link;

/** A link is live, paused or archived; only {@link #ACTIVE} redirects. */
public enum LinkStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED;

    public boolean redirects() {
        return this == ACTIVE;
    }
}
