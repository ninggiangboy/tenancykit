package dev.ngb.workflow.definitions.domain.expression;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 * The expression engine's only test of behaviour: every case in {@code contracts/workflow/expression-vectors/*.json}.
 *
 * <p>There is deliberately no second, hand-written set of operator tests: a case the contract does not carry is not a
 * behaviour the engine has to reproduce, and two sets would drift. A unit test here may cover only a checker or parser
 * message, never an operator's meaning.
 */
class ExpressionVectorTest {

    private static final List<String> FILES =
            List.of("arithmetic", "logic", "text", "collections", "references", "limits");

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
        Map<String, Object> environment = object(test.environment);
        Map<String, Object> steps = object(environment.get("steps"));
        Map<String, Object> vars = object(environment.get("vars"));
        boolean insideIteration = environment.containsKey("item") || environment.get("index") != null;

        ExprParser.Result parsed = ExprParser.parse(test.expr, "/expr");
        List<Problem> problems = new ArrayList<>(parsed.problems());
        if (parsed.expression() != null) {
            problems.addAll(ExprChecker.problems(
                    parsed.expression(),
                    "/expr",
                    new ExprChecker.Names(steps.keySet(), vars.keySet(), insideIteration)));
        }

        if (!test.expectedErrors.isEmpty()) {
            assertThat(describe(problems))
                    .as(test.name + ": the expression fails at the pointers the contract names")
                    .isEqualTo(test.expectedErrors);
            return;
        }

        assertThat(describe(problems))
                .as(test.name + ": a valid case must not carry a structural error")
                .isEmpty();

        Value actual = Evaluator.evaluate(
                parsed.expression(),
                new MapEnvironment(
                        environment.get("input"), steps, vars, environment.get("item"), index(environment.get("index"))));
        Value expected = Value.of(test.expectedValue);
        assertThat(Value.same(actual, expected))
                .as(test.name + ": expected " + Value.json(expected) + " but was " + Value.json(actual))
                .isTrue();
    }

    private Map<String, Long> casesByOperator() {
        Map<String, Long> names = new LinkedHashMap<>();
        for (String file : FILES) {
            for (Case test : cases(file)) {
                String document = json.writeValueAsString(test.expr);
                Stream.of(Operator.values())
                        .filter(operator -> document.contains("\"" + operator.wireName() + "\""))
                        .forEach(operator -> names.merge(operator.wireName(), 1L, Long::sum));
            }
        }
        return names;
    }

    /** The pointer and code of each problem, which is what the contract asserts; a message is not part of it. */
    private static List<String> describe(List<Problem> problems) {
        return problems.stream()
                .map(problem -> problem.pointer() + " " + problem.code())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> members ? (Map<String, Object>) members : Map.of();
    }

    private static Integer index(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private List<Case> cases(String file) {
        JsonNode array = read("/workflow/expression-vectors/" + file + ".json");
        List<Case> cases = new ArrayList<>(array.size());
        array.forEach(node -> cases.add(testOf(node)));
        return cases;
    }

    private Case testOf(JsonNode node) {
        JsonNode expected = node.get("expected");
        List<String> errors = new ArrayList<>();
        JsonNode errorsNode = expected.get("errors");
        if (errorsNode != null) {
            errorsNode.forEach(error -> errors.add(
                    error.get("pointer").asString() + " " + error.get("code").asString()));
        }
        errors.sort(Comparator.naturalOrder());
        return new Case(
                node.get("name").asString(),
                value(node.get("expr")),
                value(node.get("environment")),
                expected.has("value") ? value(expected.get("value")) : null,
                List.copyOf(errors));
    }

    private Object value(JsonNode node) {
        return node == null || node.isNull() ? null : json.convertValue(node, Object.class);
    }

    private JsonNode read(String resource) {
        try (InputStream stream = ExpressionVectorTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource
                        + " is not on the classpath; the build packages contracts/workflow");
            }
            return json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + resource, failure);
        }
    }

    /** One vector case, as the contract states it. */
    private record Case(
            String name, Object expr, Object environment, Object expectedValue, List<String> expectedErrors) {}
}
