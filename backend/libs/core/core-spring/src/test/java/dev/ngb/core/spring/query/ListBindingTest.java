package dev.ngb.core.spring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Op;
import dev.ngb.core.query.QueryParser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

class ListBindingTest {

    static final Field<UUID> ID = DSL.field(DSL.name("payments", "id"), SQLDataType.UUID.nullable(false));
    static final Field<UUID> WALLET_ID = DSL.field(DSL.name("payments", "wallet_id"), SQLDataType.UUID.nullable(false));
    static final Field<String> STATUS = DSL.field(DSL.name("payments", "status"), SQLDataType.VARCHAR.nullable(false));
    static final Field<Long> AMOUNT = DSL.field(DSL.name("payments", "amount"), SQLDataType.BIGINT.nullable(false));
    static final Field<OffsetDateTime> CREATED_AT = DSL.field(DSL.name("payments", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(false));
    static final Field<String> REFERENCE = DSL.field(DSL.name("payments", "reference"), SQLDataType.VARCHAR.nullable(true));
    static final Field<OffsetDateTime> CANCELED_AT = DSL.field(DSL.name("payments", "canceled_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.nullable(true));
    static final org.jooq.Table<?> WALLETS = DSL.table(DSL.name("wallets"));
    static final Field<UUID> W_ID = DSL.field(DSL.name("wallets", "id"), SQLDataType.UUID.nullable(false));
    static final Field<UUID> W_CUSTOMER = DSL.field(DSL.name("wallets", "customer_id"), SQLDataType.UUID.nullable(false));

    static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("status", "OPEN", "PAID"),
            FieldSpec.of("amount", FieldType.LONG).asSortable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("reference", FieldType.STRING).asNullable().with(Op.CONTAINS),
            FieldSpec.of("canceled_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("wallet.customer_id", FieldType.UUID));

    static ListBinding binding() {
        return ListBinding.of(SCHEMA)
                .column("id", ID).column("status", STATUS).column("amount", AMOUNT).column("created_at", CREATED_AT)
                .column("reference", REFERENCE)
                .column("canceled_at", CANCELED_AT)
                .related("wallet.customer_id", W_CUSTOMER,
                        inner -> DSL.exists(DSL.selectOne().from(WALLETS).where(W_ID.eq(WALLET_ID)).and(inner)))
                .build();
    }

    private static final DSLContext DSL_CONTEXT = DSL.using(SQLDialect.POSTGRES,
            new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER).withRenderFormatted(false));

    static String sql(Condition condition) {
        return DSL_CONTEXT.renderInlined(condition).replaceAll("\\s+", " ");
    }

    static ListQuery parse(String... keyValues) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], List.of(keyValues[i + 1]));
        }
        return QueryParser.parse(SCHEMA, params);
    }

    @Test
    void equalityAndRangeAreAndedAndTyped() {
        ListQuery query = parse("filter[status][eq]", "OPEN", "filter[amount][gte]", "1000", "filter[created_at][lt]", "2026-11-16T00:00:00Z");
        String sql = sql(binding().where(query));
        assertThat(sql).contains("payments.status = 'OPEN'").contains("payments.amount >= 1000")
                .contains("payments.created_at < ").contains("2026-11-16").contains(" and ");
    }

    @Test
    void inNotInAndIsNull() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String sql = sql(binding().where(parse("filter[status][in]", "OPEN,PAID", "filter[amount][nin]", "1,2", "filter[reference][is_null]", "false",
                "filter[id][in]", a + "," + b)));
        assertThat(sql).contains("payments.status in ('OPEN', 'PAID')").contains("payments.amount not in (1, 2)")
                .contains("payments.reference is not null").contains(a.toString()).contains(b.toString());
    }

    @Test
    void isNullNeverBindsAValueOfTheColumn() {
        assertThat(sql(binding().where(parse("filter[canceled_at][is_null]", "true")))).isEqualTo("payments.canceled_at is null");
        assertThat(sql(binding().where(parse("filter[canceled_at][is_null]", "false")))).isEqualTo("payments.canceled_at is not null");
    }

    @Test
    void textMatchesAreCaseInsensitiveAndEscapeWildcards() {
        String sql = sql(binding().where(parse("filter[reference][contains]", "50%_off\\x")));
        assertThat(sql).contains("payments.reference ilike '%50\\%\\_off\\\\x%'").contains("escape '\\'");
    }

    @Test
    void anOrBranchIsGroupedAndAndedWithTheRest() {
        UUID customer = UUID.randomUUID();
        ListQuery query = parse("filter[amount][gte]", "5", "filter[or][0][status][eq]", "OPEN", "filter[or][1][amount][lt]", "2");
        String sql = sql(binding().where(query));
        assertThat(sql).contains("payments.amount >= 5").contains("(payments.status = 'OPEN' or payments.amount < 2)");
        assertThat(customer).isNotNull();
    }

    @Test
    void aRelatedFieldBecomesAnExistsSubquery() {
        UUID customer = UUID.randomUUID();
        String sql = sql(binding().where(parse("filter[wallet.customer_id][eq]", customer.toString())));
        assertThat(sql).contains("exists (select 1 as one from wallets where (wallets.id = payments.wallet_id and wallets.customer_id = cast('" + customer + "' as uuid)))");
    }

    @Test
    void noFilterMeansNoCondition() {
        assertThat(sql(binding().where(parse()))).isIn("true", "1 = 1", "");
    }

    @Test
    void theOrderFollowsTheRequestAndEndsWithTheTieBreaker() {
        assertThat(binding().order(parse("sort", "-amount,created_at")).stream().map(DSL_CONTEXT::renderInlined).toList())
                .containsExactly("payments.amount desc", "payments.created_at asc", "payments.id asc");
        assertThat(binding().order(parse()).stream().map(DSL_CONTEXT::renderInlined).toList())
                .containsExactly("payments.created_at desc", "payments.id asc");
    }

    @Test
    void theKeysetConditionHonoursMixedDirections() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-16T10:00:00Z");
        String cursor = parse("sort", "-amount,created_at").nextCursor(List.of(750L, at, id));
        String sql = sql(binding().after(parse("sort", "-amount,created_at", "cursor", cursor)));
        java.util.regex.Matcher timestamp = java.util.regex.Pattern.compile("timestamp with time zone '([^']+)'").matcher(sql);
        assertThat(timestamp.find()).isTrue();
        // The same instant, whatever zone the JVM renders it in.
        assertThat(OffsetDateTime.parse(timestamp.group(1).replace(' ', 'T')).toInstant()).isEqualTo(at);
        String normalized = sql.replaceAll("timestamp with time zone '[^']+'", "TS").replaceAll("cast\\('([^']+)' as uuid\\)", "'$1'");
        // amount is descending, created_at ascending, the tie-breaker ascending: each later key only counts on equality.
        assertThat(normalized).isEqualTo("(payments.amount < 750 or (payments.amount = 750 and (payments.created_at > TS or "
                + "(payments.created_at = TS and payments.id > '" + id + "'))))");
    }

    @Test
    void sortValuesComeBackInTheFormACursorStores() {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 16, 10, 0, 0, 123_000_000, ZoneOffset.ofHours(7));
        Record row = DSL_CONTEXT.newRecord(ID, WALLET_ID, STATUS, AMOUNT, CREATED_AT, REFERENCE);
        row.set(ID, id);
        row.set(AMOUNT, 42L);
        row.set(CREATED_AT, at);
        assertThat(binding().sortValues(row, parse("sort", "amount"))).containsExactly(42L, id);
        assertThat(binding().sortValues(row, parse())).containsExactly(at.toInstant(), id);
    }

    @Test
    void aSchemaFieldWithoutABindingIsRefused() {
        assertThatThrownBy(() -> ListBinding.of(SCHEMA).column("id", ID).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unbound").hasMessageContaining("status");
    }

    @Test
    void aBindingForAnUnknownFieldIsRefused() {
        assertThatThrownBy(() -> ListBinding.of(FilterSchema.of("id", "id", FieldSpec.of("id", FieldType.UUID).asSortable()))
                .column("id", ID).column("stray", STATUS).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stray");
    }

    @Test
    void aColumnWhoseNullabilityDiffersFromTheFieldIsRefused() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("reference", FieldType.STRING));
        assertThatThrownBy(() -> ListBinding.of(schema).column("id", ID).column("reference", REFERENCE).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reference").hasMessageContaining("nullable");
    }

    @Test
    void aFieldCannotBeBoundTwice() {
        assertThatThrownBy(() -> ListBinding.of(SCHEMA).column("id", ID).column("id", ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
