package dev.ngb.core.query;

import java.util.List;

/**
 * A parsed and validated list request.
 *
 * @param all predicates that must all hold
 * @param anyOf OR branches: at least one branch must hold, and a branch holds when all its predicates do; empty means
 *     no OR constraint
 * @param sort the ordering, tie-breaker included, so it is total
 * @param limit the page size, within {@code [1, PageSize.MAX]}
 * @param offset rows to skip, zero when a cursor positions the page
 * @param cursorValues the sort values of the last row of the previous page, or null for a first or offset page
 * @param pageInfo whether the response carries paging facts
 * @param shape a digest of the filter and sort a cursor was issued for
 */
public record ListQuery(
        List<Filter> all,
        List<List<Filter>> anyOf,
        List<SortKey> sort,
        int limit,
        int offset,
        List<Object> cursorValues,
        boolean pageInfo,
        String shape) {

    public ListQuery {
        all = List.copyOf(all);
        anyOf = anyOf.stream().map(List::copyOf).toList();
        sort = List.copyOf(sort);
        cursorValues = cursorValues == null ? null : List.copyOf(cursorValues);
    }

    /** @return true when a cursor positions this page */
    public boolean seeking() {
        return cursorValues != null;
    }

    /** @return the cursor that continues after a row whose sort values are {@code rowValues} */
    public String nextCursor(List<Object> rowValues) {
        return Cursor.encode(shape, sort, rowValues);
    }

    /** @return the page of a listing whose scope admits no row at all, shaped as the query asks */
    public <T> Page<T> emptyPage() {
        return new Page<>(List.of(), pageInfo ? new PageInfo(limit, offset > 0 ? offset : null, 0, null) : null);
    }
}
