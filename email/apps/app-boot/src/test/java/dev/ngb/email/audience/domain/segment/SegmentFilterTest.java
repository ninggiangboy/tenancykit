package dev.ngb.email.audience.domain.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The filter language is closed: an operator, a depth, a predicate count and a field name are all bounded. */
class SegmentFilterTest {

    @Test
    void aNestedFilterPasses() {
        Map<String, Object> filter = Map.of(
                "and", List.of(
                        Map.of("field", "country", "op", "eq", "value", "VN"),
                        Map.of("or", List.of(
                                Map.of("field", "plan", "op", "in", "value", List.of("pro", "team")),
                                Map.of("field", "trial_days", "op", "lt", "value", 7))),
                        Map.of("not", Map.of("field", "churned", "op", "exists", "value", true))));
        assertThat(SegmentFilter.validate(filter)).isEqualTo(filter);
    }

    @Test
    void theBuiltInFieldsAndInGroupPass() {
        assertThat(SegmentFilter.validate(Map.of("field", "email_domain", "op", "eq", "value", "example.com")))
                .isNotNull();
        assertThat(SegmentFilter.validate(Map.of("field", "created_at", "op", "gte", "value", "2026-01-01T00:00:00Z")))
                .isNotNull();
        assertThat(SegmentFilter.validate(Map.of(
                        "field", "group", "op", "in_group", "value", "00000000-0000-7000-8000-000000000001")))
                .isNotNull();
    }

    @Test
    void aFilterDeeperThanFourLevelsIsRefused() {
        Map<String, Object> node = Map.of("field", "country", "op", "eq", "value", "VN");
        for (int i = 0; i < 4; i++) {
            node = Map.of("not", node);
        }
        Map<String, Object> tooDeep = node;
        assertThatThrownBy(() -> SegmentFilter.validate(tooDeep)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void moreThanTwentyPredicatesAreRefused() {
        List<Map<String, Object>> predicates = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            predicates.add(Map.of("field", "country", "op", "eq", "value", "VN"));
        }
        Map<String, Object> filter = Map.of("and", predicates);
        assertThatThrownBy(() -> SegmentFilter.validate(filter)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUnknownOperatorIsRefused() {
        assertThatThrownBy(() -> SegmentFilter.validate(
                        Map.of("field", "country", "op", "regex", "value", ".*")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aFieldThatIsNotAnIdentifierIsRefused() {
        assertThatThrownBy(() -> SegmentFilter.validate(
                        Map.of("field", "country; drop table receivers", "op", "eq", "value", "VN")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SegmentFilter.validate(
                        Map.of("field", "Country", "op", "eq", "value", "VN")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void inRequiresANonEmptyBoundedList() {
        assertThatThrownBy(() -> SegmentFilter.validate(
                        Map.of("field", "plan", "op", "in", "value", List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void orderingABuiltInTextFieldIsRefusedAtWriteTime() {
        // The compiler can only order a numeric attribute or an instant, so a stored filter must not claim otherwise:
        // a campaign that read such a filter would fail at snapshot time on every tick.
        assertThatThrownBy(() -> SegmentFilter.validate(
                        Map.of("field", "email_domain", "op", "gt", "value", 5)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    void orderingANumericAttributeIsAccepted() {
        assertThat(SegmentFilter.validate(Map.of("field", "age", "op", "gte", "value", 18))).isNotNull();
    }

    @Test
    void equalityAndMembershipAcceptMixedTypes() {
        // A receiver may hold a string, a number or a boolean under any key, so none of these is invalid on its own.
        assertThat(SegmentFilter.validate(Map.of("field", "age", "op", "eq", "value", "unknown"))).isNotNull();
        assertThat(SegmentFilter.validate(
                        Map.of("field", "plan", "op", "in", "value", List.of("pro", 3, true))))
                .isNotNull();
    }
}
