package dev.ngb.survey.authoring.domain.expression;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.domain.flow.Answers;
import dev.ngb.survey.authoring.domain.flow.FlowPlan;
import dev.ngb.survey.authoring.domain.flow.FlowRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The expression engine's only test of behaviour: every case in {@code contracts/survey/expression-vectors/*.json}.
 *
 * <p>There is deliberately no second, hand-written set of expression tests: a case the contract does not carry is not
 * a behaviour the future fill UI has to reproduce, and two sets would drift. A unit test here may cover only a parser
 * message, never an operator's meaning.
 */
class ExpressionVectorTest {

    private static final List<String> FILES = List.of(
            "logic", "arithmetic", "text", "collections", "piping", "scoring", "limits");

    private final JsonMapper json = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> everyVectorHolds() {
        return FILES.stream()
                .flatMap(file -> cases(file).stream())
                .map(test -> DynamicTest.dynamicTest(test.name, () -> assertHolds(test)));
    }

    @TestFactory
    Stream<DynamicTest> everyOperatorHasACase() {
        Map<String, Long> named = casesByOperator();
        return Stream.of(Operator.values())
                .map(operator -> DynamicTest.dynamicTest(operator.wireName(), () -> assertThat(
                                named.getOrDefault(operator.wireName(), 0L))
                        .as("no vector exercises " + operator.wireName())
                        .isGreaterThan(0L)));
    }

    private void assertHolds(Case test) {
        Map<String, Object> document = object(test.version);
        List<ValidationError> problems = FlowRunner.problems(document);

        if (!test.expectedErrors.isEmpty()) {
            assertThat(describe(problems))
                    .as(test.name + ": the version fails at the pointers the contract names")
                    .isEqualTo(test.expectedErrors);
            return;
        }

        assertThat(describe(problems))
                .as(test.name + ": a valid case must not carry a structural error")
                .isEmpty();

        FlowPlan plan = FlowPlan.compile(document);
        Answers answers = Answers.of(object(test.answers));
        FlowResult result = FlowRunner.run(plan, answers);

        assertThat(result.visible()).as(test.name + ": visibility").isEqualTo(test.expectedVisible);
        assertThat(result.path()).as(test.name + ": path").isEqualTo(test.expectedPath);
        assertThat(normalize(result.vars())).as(test.name + ": vars").isEqualTo(normalize(test.expectedVars));
        assertThat(number(result.score())).as(test.name + ": score").isEqualTo(number(test.expectedScore));

        Map<String, String> rendered = FlowRunner.rendered(plan, answers, null);
        test.expectedRendered.forEach((key, text) ->
                assertThat(rendered.get(key)).as(test.name + ": rendered " + key).isEqualTo(text));
    }

    private Map<String, Long> casesByOperator() {
        Map<String, Long> names = new LinkedHashMap<>();
        for (String file : FILES) {
            for (Case test : cases(file)) {
                String document = test.version.toString();
                Stream.of(Operator.values())
                        .filter(operator -> document.contains("\"" + operator.wireName() + "\""))
                        .forEach(operator -> names.merge(operator.wireName(), 1L, Long::sum));
            }
        }
        return names;
    }

    /** The pointer and code of each error, which is what the contract asserts; a message is not part of it. */
    private static List<String> describe(List<ValidationError> errors) {
        return errors.stream()
                .map(error -> error.pointer() + " " + error.code().wireName())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** Numbers compare by value: {@code 0.3} from the file and {@code 0.30} from a computation are the same score. */
    private static String number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return canonical(decimal);
        }
        return canonical(new BigDecimal(value.toString()));
    }

    private static String canonical(BigDecimal value) {
        BigDecimal rounded = value.setScale(6, RoundingMode.HALF_EVEN);
        return (rounded.signum() == 0 ? BigDecimal.ZERO : rounded.stripTrailingZeros()).toPlainString();
    }

    /** The values with every number canonical, so a file's {@code 6} and a computed {@code 6.000000} agree. */
    private static Map<String, Object> normalize(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((name, value) -> normalized.put(name, normalizeValue(value)));
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Number) {
            return number(value);
        }
        if (value instanceof List<?> members) {
            List<Object> normalized = new ArrayList<>(members.size());
            members.forEach(member -> normalized.add(normalizeValue(member)));
            return normalized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(JsonNode node) {
        return node == null || node.isNull() ? Map.of() : Json.MAPPER.convertValue(node, Map.class);
    }

    private List<Case> cases(String file) {
        JsonNode array = read("/survey/expression-vectors/" + file + ".json");
        List<Case> cases = new ArrayList<>(array.size());
        array.forEach(node -> cases.add(testOf(node)));
        return cases;
    }

    private Case testOf(JsonNode node) {
        JsonNode expected = node.get("expected");

        Map<String, Boolean> visible = new LinkedHashMap<>();
        expected.get("visible").propertyNames()
                .forEach(key -> visible.put(key, expected.get("visible").get(key).asBoolean()));

        List<String> path = new ArrayList<>();
        expected.get("path").forEach(page -> path.add(page.asString()));

        Map<String, String> rendered = new LinkedHashMap<>();
        JsonNode renderedNode = expected.get("rendered");
        if (renderedNode != null && !renderedNode.isNull()) {
            renderedNode.propertyNames().forEach(key -> rendered.put(key, renderedNode.get(key).asString()));
        }

        List<String> errors = new ArrayList<>();
        expected.get("errors").forEach(error ->
                errors.add(error.get("pointer").asString() + " " + error.get("code").asString()));
        errors.sort(Comparator.naturalOrder());

        JsonNode score = expected.get("score");
        return new Case(
                node.get("name").asString(),
                node.get("version_fragment"),
                node.get("answers"),
                visible,
                List.copyOf(path),
                object(expected.get("vars")),
                score == null || score.isNull() ? null : score.decimalValue(),
                Map.copyOf(rendered),
                List.copyOf(errors));
    }

    private JsonNode read(String resource) {
        try (InputStream stream = ExpressionVectorTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        resource + " is not on the classpath; the build packages contracts/survey");
            }
            return json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + resource, failure);
        }
    }

    /** One vector case, as the contract states it. */
    private record Case(
            String name,
            JsonNode version,
            JsonNode answers,
            Map<String, Boolean> expectedVisible,
            List<String> expectedPath,
            Map<String, Object> expectedVars,
            BigDecimal expectedScore,
            Map<String, String> expectedRendered,
            List<String> expectedErrors) {}

    /** One mapper for the document conversions, so a case and its answers are read the same way. */
    private static final class Json {
        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        private Json() {}
    }
}
