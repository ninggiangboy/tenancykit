package dev.ngb.workflow.definitions.domain.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checker's only test of behaviour: every case in {@code contracts/workflow/graph-vectors/*.json}.
 *
 * <p>An accepted case must produce no problem at all; a rejected one must produce exactly the pointers and codes the
 * contract names, so a new rule cannot appear without a case and an old rule cannot quietly disappear.
 */
class GraphVectorTest {

    private static final List<String> FILES = List.of("structure");

    private final JsonMapper json = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> everyGraphVectorHolds() {
        return FILES.stream()
                .flatMap(file -> cases(file).stream())
                .map(test -> DynamicTest.dynamicTest(test.name, () -> assertHolds(test)));
    }

    private void assertHolds(Case test) {
        GraphParser.Result parsed = GraphParser.parse(test.graph);
        List<Problem> problems = new ArrayList<>(parsed.problems());
        if (parsed.graph() != null) {
            problems.addAll(StructureChecker.problems(parsed.graph()));
        }
        assertThat(describe(problems))
                .as(test.name + ": the graph fails at the pointers the contract names")
                .isEqualTo(test.expectedErrors);
    }

    private static List<String> describe(List<Problem> problems) {
        return problems.stream()
                .map(problem -> problem.pointer() + " " + problem.code())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private List<Case> cases(String file) {
        JsonNode array = read("/workflow/graph-vectors/" + file + ".json");
        List<Case> cases = new ArrayList<>(array.size());
        array.forEach(node -> cases.add(testOf(node)));
        return cases;
    }

    private Case testOf(JsonNode node) {
        List<String> errors = new ArrayList<>();
        JsonNode expected = node.get("expected");
        JsonNode errorsNode = expected == null ? null : expected.get("errors");
        if (errorsNode != null) {
            errorsNode.forEach(error -> errors.add(
                    error.get("pointer").asString() + " " + error.get("code").asString()));
        }
        errors.sort(Comparator.naturalOrder());
        return new Case(
                node.get("name").asString(),
                json.convertValue(node.get("graph"), Object.class),
                List.copyOf(errors));
    }

    private JsonNode read(String resource) {
        try (InputStream stream = GraphVectorTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource
                        + " is not on the classpath; the build packages contracts/workflow");
            }
            return json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + resource, failure);
        }
    }

    private record Case(String name, Object graph, List<String> expectedErrors) {}
}
