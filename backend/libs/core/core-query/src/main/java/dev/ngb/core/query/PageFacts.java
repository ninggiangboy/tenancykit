package dev.ngb.core.query;

/**
 * What a listing's {@code page} carries: nothing, the cursor that continues it, or every paging fact.
 *
 * <p>A caller that walks a large listing needs only the cursor. Asking for the cursor alone keeps the read from
 * counting every row the filter matches on every page.
 */
public enum PageFacts {
    /** No {@code page}: the answer is the rows alone. */
    NONE,

    /** The page size and the cursor that continues the listing, without counting the rows it filters. */
    CURSOR,

    /** The page size, the position, the total and the cursor. */
    FULL;

    /** @return true when the answer carries a {@code page} */
    public boolean included() {
        return this != NONE;
    }

    /** @return true when the answer counts every row matching the filter */
    public boolean total() {
        return this == FULL;
    }
}
