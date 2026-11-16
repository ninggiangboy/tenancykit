package dev.ngb.crm.customers.adapter.tag.persistence;

import static dev.ngb.crm_jooq.Tables.CUSTOMER_TAGS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.query.PageInfo;
import dev.ngb.crm.customers.application.tag.query.TagReadRepository;
import dev.ngb.crm.customers.application.tag.query.TagView;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Counts and pages the distinct tags the current tenant's customers carry.
 *
 * <p>The listing is an aggregate rather than a table, so it is paged by the tag itself: the rows are ordered by tag,
 * the cursor is the last tag served, and one extra row tells whether a next page exists without counting the rest.
 */
public class JooqTagReadRepository implements TagReadRepository {

    private final DSLContext dsl;

    public JooqTagReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<TagView> list(ListQuery query) {
        Field<String> tag = CUSTOMER_TAGS.TAG;
        Field<Integer> count = DSL.count();
        Condition after = query.seeking() ? tag.gt((String) query.cursorValues().get(0)) : DSL.noCondition();
        List<TagView> rows = dsl.select(tag, count)
                .from(CUSTOMER_TAGS)
                .where(after)
                .groupBy(tag)
                .orderBy(tag.asc())
                .limit(query.limit() + 1)
                .offset(query.offset())
                .fetch(row -> new TagView(row.get(tag), row.get(count).longValue()));
        boolean more = rows.size() > query.limit();
        List<TagView> page = more ? rows.subList(0, query.limit()) : rows;
        if (!query.pageFacts().included()) {
            return new Page<>(List.copyOf(page), null);
        }
        String next = more ? query.nextCursor(List.of(page.get(page.size() - 1).tag())) : null;
        Long total = query.pageFacts().total()
                ? Long.valueOf(dsl.fetchCount(dsl.select(tag).from(CUSTOMER_TAGS).groupBy(tag)))
                : null;
        return new Page<>(
                List.copyOf(page),
                new PageInfo(query.limit(), query.offset() > 0 ? query.offset() : null, total, next));
    }
}
