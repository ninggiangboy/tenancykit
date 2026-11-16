package dev.ngb.attribution.links.domain.link;

/** A standard link is shared as it is; a personal link is one of a bulk batch, minted for one recipient. */
public enum LinkKind {
    STANDARD,
    PERSONAL
}
