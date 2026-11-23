package dev.ngb.booking.catalog.adapter.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
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
 * The preview endpoint expands a rule through the same port the materialization job uses, so a Tenant can check one
 * before creating a series. The vectors that decide the adapter's shape pass through it too, which is what makes the
 * endpoint and the job two callers of one implementation rather than two implementations.
 */
class RecurrencePreviewApiIntegrationTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path VECTORS = RepositoryPaths.repositoryRoot()
            .resolve(Path.of("contracts", "booking", "recurrence-vectors"));

    static Stream<Arguments> vectors() {
        try (Stream<Path> files = Files.list(VECTORS)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !name.equals("vector.schema.json"))
                    .sorted()
                    .map(Arguments::of)
                    .toList()
                    .stream();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void aVectorExpandsThroughTheEndpoint(String file) throws Exception {
        JsonNode vector = read(file);
        Session session = tenant(BookingPermissions.CALENDAR_READ);

        String body = postAs(session, "/system/v1/recurrence/preview", request(vector))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instants.length()").value(vector.get("expect").size()))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(instants(JSON.readTree(body).get("instants")))
                .containsExactlyElementsOf(instants(vector.get("expect")));
    }

    @Test
    void aRuleTheExpanderCannotReadIsRefusedWith422AndItsCode() throws Exception {
        Session session = tenant(BookingPermissions.CALENDAR_READ);

        postAs(session, "/system/v1/recurrence/preview", """
                {"zone":"Europe/Berlin","dtstart":"2026-01-05T09:00:00","rule":["RRULE:FREQ=NEVER"],
                 "from":"2026-01-01T00:00:00Z","to":"2026-02-01T00:00:00Z"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("invalid-recurrence"));
    }

    @Test
    void aRuleThatExpandsBeyondTheCapIsRefusedWithTheInvalidRecurrenceCode() throws Exception {
        Session session = tenant(BookingPermissions.CALENDAR_READ);

        postAs(session, "/system/v1/recurrence/preview", """
                {"zone":"UTC","dtstart":"2026-01-05T09:00:00","rule":["RRULE:FREQ=MINUTELY"],
                 "from":"2026-01-01T00:00:00Z","to":"2026-02-01T00:00:00Z"}
                """)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("invalid-recurrence"));
    }

    private static String request(JsonNode vector) {
        List<String> rule = new ArrayList<>();
        vector.get("rule").valueStream().forEach(line -> rule.add("\"" + line.asString() + "\""));
        return """
                {"zone":"%s","dtstart":"%s","rule":[%s],"from":"%s","to":"%s"}
                """
                .formatted(
                        vector.get("zone").asString(),
                        vector.get("dtstart").asString(),
                        String.join(",", rule),
                        vector.get("from").asString(),
                        vector.get("to").asString());
    }

    private static List<Instant> instants(JsonNode array) {
        List<Instant> instants = new ArrayList<>();
        array.valueStream().forEach(value -> instants.add(Instant.parse(value.asString())));
        return List.copyOf(instants);
    }

    private static JsonNode read(String file) {
        try {
            return JSON.readTree(Files.readString(VECTORS.resolve(file), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
