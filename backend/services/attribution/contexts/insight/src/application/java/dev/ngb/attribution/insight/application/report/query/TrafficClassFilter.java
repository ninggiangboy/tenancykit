package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which traffic classes a report includes. A report is human by default; {@code include=bot,suspect} adds them, and the
 * response names how many bot clicks the default excluded.
 */
public record TrafficClassFilter(Set<String> classes, boolean humanOnly) {

    public TrafficClassFilter {
        classes = Set.copyOf(classes);
    }

    public static TrafficClassFilter of(String include) {
        Set<String> classes = new LinkedHashSet<>();
        classes.add("HUMAN");
        if (include != null && !include.isBlank()) {
            for (String part : include.split(",")) {
                switch (part.strip().toLowerCase(Locale.ROOT)) {
                    case "" -> {
                        continue;
                    }
                    case "bot" -> classes.add("BOT");
                    case "suspect" -> classes.add("SUSPECT");
                    default -> throw new BadRequestException("unknown traffic class: " + part);
                }
            }
        }
        return new TrafficClassFilter(classes, classes.size() == 1);
    }

    public static TrafficClassFilter human() {
        return new TrafficClassFilter(Set.of("HUMAN"), true);
    }

    /** The label a response carries, such as {@code HUMAN} or {@code HUMAN,BOT}. */
    public String label() {
        return classes.stream().sorted().collect(Collectors.joining(","));
    }
}
