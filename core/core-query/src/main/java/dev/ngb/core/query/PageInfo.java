package dev.ngb.core.query;

/**
 * The paging facts a caller asks for with {@code page_info=true}. The last component is named as it appears on the
 * wire ({@code next_cursor}), so the response needs no serializer annotation and this module stays plain Java.
 *
 * @param limit the page size that was applied
 * @param offset the rows skipped, or null when the page was positioned by a cursor or is the first one
 * @param total the number of rows that match the filter
 * @param next_cursor the cursor of the next page, or null on the last page
 */
public record PageInfo(int limit, Integer offset, long total, String next_cursor) {

    /** @return the cursor of the next page, or null on the last page */
    public String nextCursor() {
        return next_cursor;
    }
}
