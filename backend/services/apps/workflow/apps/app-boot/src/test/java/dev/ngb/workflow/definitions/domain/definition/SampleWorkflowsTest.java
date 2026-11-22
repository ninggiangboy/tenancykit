package dev.ngb.workflow.definitions.domain.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four sample workflows are fixtures a Tenant can publish as they stand. They are checked the way publishing checks
 * a draft, so a sample cannot drift away from the graph language it demonstrates.
 */
class SampleWorkflowsTest {

    private static final List<String> SAMPLES =
            List.of("payment-dunning", "refund-approval", "points-nudge", "erasure-fanout");

    @Test
    void everySampleIsAPublishableGraph() {
        JsonMapper json = JsonMapper.builder().build();
        for (String sample : SAMPLES) {
            Object graph = read(json, sample);
            GraphParser.Result parsed = GraphParser.parse(graph);
            List<Problem> problems = new ArrayList<>(parsed.problems());
            if (parsed.graph() != null) {
                problems.addAll(StructureChecker.problems(parsed.graph()));
            }
            assertThat(problems).as(sample + " is a graph a Tenant can publish").isEmpty();
        }
    }

    private static Object read(JsonMapper json, String sample) {
        String resource = "/samples/" + sample + ".json";
        try (InputStream stream = SampleWorkflowsTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            return json.readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
