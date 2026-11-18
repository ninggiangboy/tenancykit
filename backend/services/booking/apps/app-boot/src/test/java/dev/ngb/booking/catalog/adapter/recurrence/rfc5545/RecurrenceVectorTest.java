package dev.ngb.booking.catalog.adapter.recurrence.rfc5545;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.support.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every vector under {@code contracts/booking/recurrence-vectors/} expanded through the real port adapter.
 *
 * <p>The vectors are computed from the tz database and the RFC text rather than copied from an implementation, so the
 * expectation is the thing under test: a vector that fails is a bug in the adapter, never a vector to be adjusted.
 * Every JSON file in the directory is run, and the schema beside them is skipped by name.
 */
class RecurrenceVectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path VECTORS = RepositoryPaths.repositoryRoot()
            .resolve(Path.of("contracts", "booking", "recurrence-vectors"));

    private static final String SCHEMA = "vector.schema.json";

    private final RecurrenceValidator recurrence = new RecurrenceValidator(new Rfc5545RecurrenceExpander());

    static Stream<Arguments> vectors() {
        List<Arguments> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(VECTORS)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals(SCHEMA))
                    .sorted()
                    .toList()) {
                JsonNode vector = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                cases.add(Arguments.of(
                        file.getFileName().toString(),
                        vector.get("name").asString(),
                        vector.get("zone").asString(),
                        vector.get("dtstart").asString(),
                        strings(vector.get("rule")),
                        Instant.parse(vector.get("from").asString()),
                        Instant.parse(vector.get("to").asString()),
                        instants(vector.get("expect"))));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("vectors")
    void everyVectorExpandsExactly(
            String file,
            String name,
            String zone,
            String dtstart,
            List<String> rule,
            Instant from,
            Instant to,
            List<Instant> expect) {
        assertThat(recurrence.expand(zone, dtstart, rule, from, to))
                .as(name)
                .containsExactlyElementsOf(expect);
    }

    @Test
    void theDirectoryHoldsEveryVectorItIsMeantTo() {
        assertThat(vectors().count())
                .as("a scan that finds no vector would pass vacuously")
                .isEqualTo(12);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.valueStream().forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }

    private static List<Instant> instants(JsonNode array) {
        List<Instant> values = new ArrayList<>();
        array.valueStream().forEach(value -> values.add(Instant.parse(value.asString())));
        return List.copyOf(values);
    }
}
