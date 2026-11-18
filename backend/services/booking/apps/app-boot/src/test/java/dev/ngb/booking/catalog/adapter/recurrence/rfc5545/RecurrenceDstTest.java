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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a transition does to the instant a local start becomes: the wall-clock hour the Tenant configured survives, the
 * UTC instant moves by the zone's own shift, and a local time that happens twice is taken at its first occurrence.
 *
 * <p>The three spring-forward cases move by an hour in New York and London and by thirty minutes on Lord Howe, which
 * an implementation that assumed a one-hour transition could not produce. The fall-back case is the one RFC 5545
 * settles explicitly: an ambiguous local time means the first of the two, so 01:30 on the day New York repeats it is
 * still the earlier offset.
 */
class RecurrenceDstTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path VECTORS = RepositoryPaths.repositoryRoot()
            .resolve(Path.of("contracts", "booking", "recurrence-vectors"));

    private final RecurrenceValidator recurrence = new RecurrenceValidator(new Rfc5545RecurrenceExpander());

    @Test
    void aSpringForwardKeepsTheLocalHourInNewYork() {
        List<Instant> instants = expand("dst-new-york.json");
        assertThat(instants).containsExactly(
                Instant.parse("2026-03-06T14:00:00Z"),
                Instant.parse("2026-03-13T13:00:00Z"),
                Instant.parse("2026-03-20T13:00:00Z"));
        assertLocalHour(instants, "America/New_York", 9);
    }

    @Test
    void aSpringForwardKeepsTheLocalHourInLondon() {
        List<Instant> instants = expand("dst-london.json");
        assertThat(instants).containsExactly(
                Instant.parse("2026-03-20T09:00:00Z"),
                Instant.parse("2026-03-27T09:00:00Z"),
                Instant.parse("2026-04-03T08:00:00Z"));
        assertLocalHour(instants, "Europe/London", 9);
    }

    @Test
    void aHalfHourTransitionMovesTheInstantByHalfAnHourOnLordHowe() {
        List<Instant> instants = expand("dst-lord-howe.json");
        assertThat(instants).containsExactly(
                Instant.parse("2026-09-26T22:30:00Z"),
                Instant.parse("2026-10-03T22:00:00Z"),
                Instant.parse("2026-10-10T22:00:00Z"));
        assertLocalHour(instants, "Australia/Lord_Howe", 9);
    }

    @Test
    void aStartInsideTheGapMovesForwardByTheGap() {
        List<Instant> instants = expand("dst-gap.json");
        assertThat(instants).containsExactly(
                Instant.parse("2026-03-08T07:30:00Z"), Instant.parse("2026-03-09T06:30:00Z"));
        assertThat(ZoneId.of("America/New_York").getRules().getOffset(instants.get(0)))
                .as("a nonexistent local time moves forward by the gap")
                .isEqualTo(ZoneOffset.ofHours(-4));
    }

    @Test
    void aStartInsideTheOverlapTakesTheFirstOccurrence() {
        List<Instant> instants = expand("dst-overlap.json");
        assertThat(instants).containsExactly(
                Instant.parse("2026-11-01T05:30:00Z"), Instant.parse("2026-11-02T06:30:00Z"));
        ZoneId zone = ZoneId.of("America/New_York");
        assertThat(zone.getRules().getOffset(instants.get(0)))
                .as("an ambiguous local time takes the occurrence before the change")
                .isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(zone.getRules().getOffset(instants.get(1))).isEqualTo(ZoneOffset.ofHours(-5));
    }

    private void assertLocalHour(List<Instant> instants, String zone, int hour) {
        ZoneId zoneId = ZoneId.of(zone);
        for (Instant instant : instants) {
            assertThat(LocalDateTime.ofInstant(instant, zoneId).getHour())
                    .as("the wall-clock hour a Tenant configured is preserved")
                    .isEqualTo(hour);
        }
    }

    private List<Instant> expand(String file) {
        try {
            JsonNode vector = JSON.readTree(
                    Files.readString(VECTORS.resolve(file), StandardCharsets.UTF_8));
            List<String> rule = new ArrayList<>();
            vector.get("rule").valueStream().forEach(line -> rule.add(line.asString()));
            return recurrence.expand(
                    vector.get("zone").asString(),
                    vector.get("dtstart").asString(),
                    rule,
                    Instant.parse(vector.get("from").asString()),
                    Instant.parse(vector.get("to").asString()));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
