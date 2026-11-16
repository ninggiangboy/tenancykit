package dev.ngb.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryParserTest {

    enum Status { OPEN, PAID }

    private static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("status", Status.class),
            FieldSpec.of("reference", FieldType.STRING).asNullable().with(Op.CONTAINS),
            FieldSpec.of("amount", FieldType.LONG).asSortable(),
            FieldSpec.of("price", FieldType.DECIMAL),
            FieldSpec.of("paid", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("wallet.customer_id", FieldType.UUID));

    private static ListQuery parse(String... keyValues) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.computeIfAbsent(keyValues[i], k -> new java.util.ArrayList<>()).add(keyValues[i + 1]);
        }
        return QueryParser.parse(SCHEMA, params);
    }

    private static void rejects(String message, String... keyValues) {
        assertThatThrownBy(() -> parse(keyValues)).isInstanceOf(BadRequestException.class).hasMessageContaining(message);
    }

    @Test
    void noParametersGivesTheDefaults() {
        ListQuery query = parse();
        assertThat(query.all()).isEmpty();
        assertThat(query.anyOf()).isEmpty();
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.offset()).isZero();
        assertThat(query.seeking()).isFalse();
        assertThat(query.pageInfo()).isFalse();
        assertThat(query.sort()).extracting(SortKey::wire).containsExactly("-created_at", "id");
    }

    @Test
    void typedValuesAreParsedPerField() {
        UUID customer = UUID.randomUUID();
        ListQuery query = parse(
                "filter[status][eq]", "OPEN",
                "filter[amount][gte]", "1000",
                "filter[price][lt]", "12.50",
                "filter[paid][eq]", "true",
                "filter[created_at][gte]", "2026-11-01T00:00:00Z",
                "filter[wallet.customer_id][eq]", customer.toString());
        assertThat(query.all()).hasSize(6);
        assertThat(valueOf(query, "status")).isEqualTo("OPEN");
        assertThat(valueOf(query, "amount")).isEqualTo(1000L);
        assertThat(valueOf(query, "price")).isEqualTo(new BigDecimal("12.50"));
        assertThat(valueOf(query, "paid")).isEqualTo(true);
        assertThat(valueOf(query, "created_at")).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(valueOf(query, "wallet.customer_id")).isEqualTo(customer);
    }

    private static Object valueOf(ListQuery query, String field) {
        return query.all().stream().filter(f -> f.field().name().equals(field)).findFirst().orElseThrow().value();
    }

    @Test
    void inTakesCommaSeparatedAndRepeatedValues() {
        ListQuery query = parse("filter[status][in]", "OPEN,PAID", "filter[amount][nin]", "1", "filter[amount][nin]", "2,3");
        assertThat(query.all()).extracting(f -> f.values().size()).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void isNullTakesABoolean() {
        ListQuery query = parse("filter[reference][is_null]", "true");
        assertThat(query.all().get(0).op()).isEqualTo(Op.IS_NULL);
        assertThat(query.all().get(0).value()).isEqualTo(true);
        rejects("true or false", "filter[reference][is_null]", "maybe");
        rejects("does not apply", "filter[amount][is_null]", "true");
    }

    @Test
    void orBranchesGroupByIndex() {
        ListQuery query = parse(
                "filter[wallet.customer_id][eq]", UUID.randomUUID().toString(),
                "filter[or][0][status][eq]", "OPEN",
                "filter[or][0][amount][gte]", "5",
                "filter[or][3][amount][lt]", "2");
        assertThat(query.all()).hasSize(1);
        assertThat(query.anyOf()).hasSize(2);
        assertThat(query.anyOf().get(0)).hasSize(2);
        assertThat(query.anyOf().get(1)).hasSize(1);
    }

    @Test
    void sortNamesWhitelistedFieldsAndAppendsTheTieBreaker() {
        assertThat(parse("sort", "-amount,created_at").sort()).extracting(SortKey::wire).containsExactly("-amount", "created_at", "id");
        assertThat(parse("sort", "-id").sort()).extracting(SortKey::wire).containsExactly("-id");
    }

    @Test
    void sortRefusesUnknownRepeatedAndTooManyFields() {
        rejects("cannot be sorted", "sort", "status");
        rejects("cannot be sorted", "sort", "wallet.customer_id");
        rejects("sorted twice", "sort", "amount,-amount");
    }

    @Test
    void sortRefusesMoreThanFourFieldsButAcceptsFour() {
        FilterSchema wide = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("a", FieldType.LONG).asSortable(),
                FieldSpec.of("b", FieldType.LONG).asSortable(),
                FieldSpec.of("c", FieldType.LONG).asSortable(),
                FieldSpec.of("d", FieldType.LONG).asSortable(),
                FieldSpec.of("e", FieldType.LONG).asSortable());
        assertThat(QueryParser.parse(wide, Map.of("sort", List.of("a,b,c,d"))).sort()).hasSize(5);
        assertThatThrownBy(() -> QueryParser.parse(wide, Map.of("sort", List.of("a,b,c,d,e"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at most 4");
    }

    @Test
    void textOperatorsAreOnlyOfferedWhereDeclared() {
        parse("filter[reference][contains]", "ord-4_2%");
        parse("filter[reference][starts_with]", "ord-");
        rejects("does not apply", "filter[amount][contains]", "1");
    }

    @Test
    void unknownFieldOperatorAndParameterAreRefused() {
        rejects("cannot be filtered", "filter[stripe_payment_intent_id][eq]", "pi_1");
        rejects("is not supported", "filter[status][like]", "OPEN");
        rejects("not supported on this listing", "status", "OPEN");
        rejects("not supported on this listing", "before", UUID.randomUUID().toString());
        rejects("does not apply", "filter[status][gt]", "OPEN");
    }

    @Test
    void aWrongValueNamesTheFieldAndTheExpectedType() {
        rejects("status takes one of", "filter[status][eq]", "BOGUS");
        rejects("amount takes a whole number", "filter[amount][eq]", "ten");
        rejects("takes a UUID", "filter[wallet.customer_id][eq]", "x");
        rejects("ISO-8601", "filter[created_at][gte]", "yesterday");
        rejects("true or false", "filter[paid][eq]", "yes");
        rejects("at most 255", "filter[reference][eq]", "x".repeat(256));
    }

    @Test
    void theLimitsAreEnforced() {
        String many = String.join(",", java.util.Collections.nCopies(101, "1"));
        rejects("between 1 and 100", "filter[amount][in]", many);
        String[] predicates = new String[2 * 21];
        for (int i = 0; i < 21; i++) {
            predicates[2 * i] = "filter[or][" + i % 10 + "][amount][gte]";
            predicates[2 * i + 1] = "1";
        }
        // The same key repeated is a multi-value error, so use distinct fields through the OR index instead.
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            params.put("filter[or][" + i + "][amount][gte]", List.of("1"));
        }
        assertThatThrownBy(() -> QueryParser.parse(SCHEMA, params)).hasMessageContaining("OR branches");
        Map<String, List<String>> twenty1 = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            twenty1.put("filter[or][" + i + "][amount][gte]", List.of("1"));
            twenty1.put("filter[or][" + i + "][amount][lt]", List.of("9"));
        }
        twenty1.put("filter[price][lt]", List.of("1"));
        assertThatThrownBy(() -> QueryParser.parse(SCHEMA, twenty1)).hasMessageContaining("at most 20 predicates");
    }

    @Test
    void limitIsClampedAndOffsetIsBounded() {
        assertThat(parse("limit", "500").limit()).isEqualTo(200);
        assertThat(parse("limit", "0").limit()).isEqualTo(1);
        assertThat(parse("offset", "10000").offset()).isEqualTo(10_000);
        rejects("between 0 and 10000", "offset", "10001");
        rejects("between 0 and 10000", "offset", "-1");
        rejects("whole number", "limit", "many");
    }

    @Test
    void pageInfoIsOptIn() {
        assertThat(parse("page_info", "true").pageInfo()).isTrue();
        assertThat(parse("page_info", "false").pageInfo()).isFalse();
        rejects("true or false", "page_info", "1");
    }

    @Test
    void aScalarParameterTakesExactlyOneValue() {
        rejects("exactly one value", "sort", "amount", "sort", "-amount");
        rejects("exactly one value", "filter[status][eq]", "OPEN", "filter[status][eq]", "PAID");
    }

    @Test
    void aCursorContinuesTheSameFilterAndSort() {
        ListQuery first = parse("filter[status][eq]", "OPEN", "sort", "-amount");
        UUID id = UUID.randomUUID();
        String cursor = first.nextCursor(List.of(750L, id));
        ListQuery next = parse("filter[status][eq]", "OPEN", "sort", "-amount", "cursor", cursor, "limit", "10");
        assertThat(next.seeking()).isTrue();
        assertThat(next.cursorValues()).containsExactly(750L, id);
    }

    @Test
    void aCursorRoundTripsEveryType() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("name", FieldType.STRING).asSortable(),
                FieldSpec.of("price", FieldType.DECIMAL).asSortable(),
                FieldSpec.of("at", FieldType.INSTANT).asSortable());
        ListQuery query = QueryParser.parse(schema, Map.of("sort", List.of("name,-price,at")));
        UUID id = UUID.randomUUID();
        List<Object> row = List.of("a|b\nc", new BigDecimal("1E+2"), Instant.parse("2026-11-16T10:15:30.123456Z"), id);
        String cursor = query.nextCursor(row);
        ListQuery next = QueryParser.parse(schema, Map.of("sort", List.of("name,-price,at"), "cursor", List.of(cursor)));
        assertThat(next.cursorValues().get(0)).isEqualTo("a|b\nc");
        assertThat((BigDecimal) next.cursorValues().get(1)).isEqualByComparingTo("100");
        assertThat(next.cursorValues().get(2)).isEqualTo(Instant.parse("2026-11-16T10:15:30.123456Z"));
        assertThat(next.cursorValues().get(3)).isEqualTo(id);
    }

    @Test
    void aCursorIsRefusedWithAnotherFilterOrSort() {
        String cursor = parse("filter[status][eq]", "OPEN").nextCursor(List.of(Instant.parse("2026-11-16T10:15:30Z"), UUID.randomUUID()));
        rejects("different filter or sort", "filter[status][eq]", "PAID", "cursor", cursor);
        rejects("different filter or sort", "filter[status][eq]", "OPEN", "sort", "amount", "cursor", cursor);
        rejects("different filter or sort", "cursor", cursor);
    }

    @Test
    void theShapeIgnoresParameterOrderAndPaging() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-16T10:15:30Z");
        String cursor = parse("filter[status][eq]", "OPEN", "filter[amount][gte]", "5").nextCursor(List.of(at, id));
        ListQuery next = parse("filter[amount][gte]", "5", "filter[status][eq]", "OPEN", "cursor", cursor, "limit", "7", "page_info", "true");
        assertThat(next.cursorValues()).containsExactly(at, id);
    }

    @Test
    void aGarbledOrForgedCursorIsRefused() {
        rejects("not valid", "cursor", "%%%");
        rejects("not valid", "cursor", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("1|x".getBytes()));
        String good = parse().nextCursor(List.of(Instant.parse("2026-11-16T10:15:30Z"), UUID.randomUUID()));
        rejects("not valid", "cursor", good.substring(0, good.length() - 6));
    }

    @Test
    void aCursorCannotBeCombinedWithAnOffset() {
        String cursor = parse().nextCursor(List.of(Instant.parse("2026-11-16T10:15:30Z"), UUID.randomUUID()));
        rejects("either offset or cursor", "cursor", cursor, "offset", "10");
    }

    @Test
    void passthroughParametersAreLeftToTheEndpoint() {
        Map<String, List<String>> params = Map.of("metric", List.of("api_calls"));
        assertThat(QueryParser.parse(SCHEMA, params, Set.of("metric")).all()).isEmpty();
        assertThatThrownBy(() -> QueryParser.parse(SCHEMA, params)).isInstanceOf(BadRequestException.class);
    }
}
