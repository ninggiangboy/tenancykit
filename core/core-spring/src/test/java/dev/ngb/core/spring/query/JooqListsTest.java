package dev.ngb.core.spring.query;

import static dev.ngb.core.spring.query.ListBindingTest.AMOUNT;
import static dev.ngb.core.spring.query.ListBindingTest.CREATED_AT;
import static dev.ngb.core.spring.query.ListBindingTest.ID;
import static dev.ngb.core.spring.query.ListBindingTest.REFERENCE;
import static dev.ngb.core.spring.query.ListBindingTest.STATUS;
import static dev.ngb.core.spring.query.ListBindingTest.WALLET_ID;
import static dev.ngb.core.spring.query.ListBindingTest.binding;
import static dev.ngb.core.spring.query.ListBindingTest.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class JooqListsTest {

    private static final Table<Record> PAYMENTS = DSL.table(DSL.name("payments"));
    private static final long TOTAL = 87;
    private static final Field<?>[] COLUMNS = {ID, WALLET_ID, STATUS, AMOUNT, CREATED_AT, REFERENCE};

    /** A database that answers the page query with {@code rows} and the count query with {@link #TOTAL}. */
    private record Fake(DSLContext dsl, List<String> sql, List<List<Object>> bindings) {

        @SuppressWarnings("unchecked")
        static Fake serving(int rows) {
            List<String> sql = new ArrayList<>();
            List<List<Object>> bindings = new ArrayList<>();
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            DSLContext dsl = DSL.using(new MockConnection(ctx -> {
                sql.add(ctx.sql());
                bindings.add(List.of(ctx.bindings()));
                if (ctx.sql().toLowerCase().contains("count(")) {
                    Field<?>[] countColumn = {DSL.field("count", SQLDataType.INTEGER)};
                    Result<Record> count = create.newResult(countColumn);
                    Record only = create.newRecord(countColumn);
                    only.set((Field<Integer>) countColumn[0], (int) TOTAL);
                    count.add(only);
                    return new MockResult[] {new MockResult(1, count)};
                }
                Result<Record> result = create.newResult(COLUMNS);
                for (int i = 0; i < rows; i++) {
                    Record row = create.newRecord(COLUMNS);
                    row.set(ID, UUID.randomUUID());
                    row.set(WALLET_ID, UUID.randomUUID());
                    row.set(STATUS, "OPEN");
                    row.set(AMOUNT, 1000L - i);
                    row.set(CREATED_AT, OffsetDateTime.of(2026, 11, 16, 10, 0, 0, 0, ZoneOffset.UTC).minusMinutes(i));
                    result.add(row);
                }
                return new MockResult[] {new MockResult(rows, result)};
            }), SQLDialect.POSTGRES);
            return new Fake(dsl, sql, bindings);
        }
    }

    private static Page<UUID> fetch(Fake fake, ListQuery query) {
        return JooqLists.fetch(fake.dsl(), PAYMENTS, DSL.noCondition(), binding(), query, row -> row.get(ID));
    }

    @Test
    void aFullWindowYieldsTheLimitAndNoPageInfoByDefault() {
        Fake fake = Fake.serving(3);
        Page<UUID> page = fetch(fake, parse("limit", "2"));
        assertThat(page.data()).hasSize(2);
        assertThat(page.page()).isNull();
        assertThat(fake.sql()).hasSize(1);
        // One extra row is requested to learn whether another page exists.
        assertThat(fake.bindings().get(0)).contains(3L);
    }

    @Test
    void pageInfoCarriesTheNextCursorAndTheTotal() {
        Fake fake = Fake.serving(3);
        ListQuery query = parse("limit", "2", "page_info", "true", "sort", "-amount");
        Page<UUID> page = fetch(fake, query);
        assertThat(page.page().limit()).isEqualTo(2);
        assertThat(page.page().offset()).isNull();
        assertThat(page.page().total()).isEqualTo(TOTAL);
        assertThat(page.page().nextCursor()).isNotBlank();
        // The cursor continues after the second row, the last one served.
        ListQuery next = parse("limit", "2", "sort", "-amount", "cursor", page.page().nextCursor());
        assertThat(next.cursorValues()).hasSize(2).first().isEqualTo(999L);
    }

    @Test
    void askingOnlyForTheCursorSkipsTheCount() {
        Fake fake = Fake.serving(3);
        Page<UUID> page = fetch(fake, parse("limit", "2", "page_info", "cursor"));

        assertThat(page.page().limit()).isEqualTo(2);
        assertThat(page.page().nextCursor()).isNotBlank();
        assertThat(page.page().total()).isNull();
        assertThat(fake.sql()).hasSize(1);
    }

    @Test
    void theLastPageHasNoNextCursor() {
        Page<UUID> page = fetch(Fake.serving(1), parse("limit", "2", "page_info", "true"));
        assertThat(page.data()).hasSize(1);
        assertThat(page.page().nextCursor()).isNull();
    }

    @Test
    void anOffsetPageReportsItsOffset() {
        Fake fake = Fake.serving(2);
        Page<UUID> page = fetch(fake, parse("limit", "2", "offset", "100", "page_info", "true"));
        assertThat(page.page().offset()).isEqualTo(100);
        assertThat(fake.bindings().get(0)).contains(100L);
    }

    @Test
    void theTotalCountsTheFilterNotThePosition() {
        Fake fake = Fake.serving(3);
        ListQuery first = parse("limit", "2", "page_info", "true", "filter[status][eq]", "OPEN");
        String cursor = fetch(fake, first).page().nextCursor();
        Fake second = Fake.serving(1);
        fetch(second, parse("limit", "2", "page_info", "true", "filter[status][eq]", "OPEN", "cursor", cursor));
        assertThat(second.sql()).hasSize(2);
        // The page query binds the cursor values as well; the count must not.
        assertThat(second.bindings().get(1)).hasSizeLessThan(second.bindings().get(0).size());
    }

    @Test
    void theScopeAlwaysApplies() {
        Fake fake = Fake.serving(0);
        JooqLists.fetch(fake.dsl(), PAYMENTS, STATUS.eq("PAID"), binding(), parse("page_info", "true"), row -> row.get(ID));
        assertThat(fake.sql()).hasSize(2).allMatch(sql -> sql.contains("status"));
    }

    @Test
    void anEmptyScopeAnswersWithoutTouchingTheDatabase() {
        assertThat(parse().<UUID>emptyPage().page()).isNull();
        Page<UUID> withInfo = parse("page_info", "true", "limit", "5").emptyPage();
        assertThat(withInfo.data()).isEmpty();
        assertThat(withInfo.page().total()).isZero();
        assertThat(withInfo.page().limit()).isEqualTo(5);
        assertThat(withInfo.page().nextCursor()).isNull();
        Page<UUID> cursorOnly = parse("page_info", "cursor", "limit", "5").emptyPage();
        assertThat(cursorOnly.page().total()).isNull();
        assertThat(cursorOnly.page().nextCursor()).isNull();
    }
}
