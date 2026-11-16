package dev.ngb.core.spring.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.query.PageInfo;
import java.util.List;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;

/** Runs a {@link ListQuery} against one table and shapes the answer as a {@link Page}. */
public final class JooqLists {

    private JooqLists() {}

    /**
     * Reads one page. The window is fetched with one extra row so a following page is known without a second query; a
     * total is counted only when the caller asked for page info, and counts every row matching the filter whatever the
     * position of the page.
     *
     * @param scope a condition the caller always applies, such as the customers a Tenant System may see
     * @param mapper turns a row into the view the listing returns
     */
    public static <R extends Record, V> Page<V> fetch(DSLContext dsl, Table<R> table, Condition scope, ListBinding binding,
            ListQuery query, Function<? super R, ? extends V> mapper) {
        return fetchPage(dsl, table, scope, binding, query, rows -> rows.stream().<V>map(mapper).toList());
    }

    /**
     * Reads one page and shapes it from the whole page, for a view that needs something of its rows at once, such as
     * a related collection fetched in one query instead of one per row.
     */
    public static <R extends Record, V> Page<V> fetchPage(DSLContext dsl, Table<R> table, Condition scope, ListBinding binding,
            ListQuery query, Function<? super List<R>, ? extends List<V>> mapper) {
        Condition filtered = scope.and(binding.where(query));
        Condition positioned = query.seeking() ? filtered.and(binding.after(query)) : filtered;
        List<R> rows = dsl.selectFrom(table)
                .where(positioned)
                .orderBy(binding.order(query))
                .limit(query.limit() + 1)
                .offset(query.offset())
                .fetch();
        boolean more = rows.size() > query.limit();
        List<R> page = more ? rows.subList(0, query.limit()) : rows;
        List<V> data = page.isEmpty() ? List.of() : List.copyOf(mapper.apply(page));
        if (!query.pageInfo()) {
            return new Page<>(data, null);
        }
        String next = more ? query.nextCursor(binding.sortValues(page.get(page.size() - 1), query)) : null;
        long total = dsl.fetchCount(table, filtered);
        return new Page<>(data, new PageInfo(query.limit(), query.offset() > 0 ? query.offset() : null, total, next));
    }
}
