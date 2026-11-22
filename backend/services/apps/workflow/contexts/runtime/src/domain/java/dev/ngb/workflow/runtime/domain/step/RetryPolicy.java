package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.workflow.definitions.api.version.GraphNode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(int max, Duration base, Duration cap, boolean jitter) {

    public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ofSeconds(2), Duration.ofMinutes(1), false);

    private static final double JITTER_SHARE = 0.1;

    public static RetryPolicy of(GraphNode node) {
        Object document = node == null ? null : node.document("retry");
        if (!(document instanceof Map<?, ?> members)) {
            return NONE;
        }
        int max = members.get("max") instanceof Number number ? Math.max(0, number.intValue()) : 0;
        return new RetryPolicy(
                max,
                duration(members.get("base"), NONE.base()),
                duration(members.get("cap"), NONE.cap()),
                Boolean.TRUE.equals(members.get("jitter")));
    }

    public boolean hasAttemptsLeft(int attempt) {
        return attempt <= max;
    }

    public Duration backoff(int attempt) {
        Duration backoff = base;
        for (int step = 1; step < attempt && backoff.compareTo(cap) < 0; step++) {
            backoff = backoff.multipliedBy(2);
        }
        if (backoff.compareTo(cap) > 0) {
            backoff = cap;
        }
        return jitter ? jittered(backoff) : backoff;
    }

    public Instant nextAvailableAt(Instant now, int attempt) {
        return now.plus(backoff(attempt));
    }

    private static Duration jittered(Duration backoff) {
        long extra = (long) (backoff.toMillis() * JITTER_SHARE * ThreadLocalRandom.current().nextDouble());
        return backoff.plusMillis(extra);
    }

    private static Duration duration(Object value, Duration fallback) {
        if (!(value instanceof String text) || text.isBlank()) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(text);
            return parsed.isNegative() ? Duration.ZERO : parsed;
        } catch (DateTimeParseException invalid) {
            return fallback;
        }
    }
}
