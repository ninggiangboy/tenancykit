package dev.ngb.email.content.domain.template;

import java.time.Duration;
import java.util.Objects;

/**
 * The hard limits a render obeys, whatever the template says.
 *
 * <p>They are the sandbox's numbers rather than tuning: a tenant writes the template and a receiver controls the data,
 * so the risk is a denial of service and markup injection, not logic. A template that breaks a limit fails the render
 * with a typed exception; it is never truncated and a partial body is never sent. The wall clock is checked by the
 * renderer's counting writer, because a library with no resource limits cannot be trusted to enforce its own.
 *
 * @param maxPartBytes the largest single template part, refused above rather than truncated
 * @param maxOutputChars the largest rendered part
 * @param maxSectionDepth how deeply sections may nest
 * @param maxIterations the total loop iterations one render may perform
 * @param maxWallClock the time one render may take
 */
public record RenderLimits(
        int maxPartBytes,
        int maxOutputChars,
        int maxSectionDepth,
        int maxIterations,
        Duration maxWallClock) {

    /** 256 KiB parts, 262144 characters of output, depth 4, 10,000 iterations, 500 ms. */
    public static final RenderLimits DEFAULT =
            new RenderLimits(256 * 1024, 262144, 4, 10_000, Duration.ofMillis(500));

    public RenderLimits {
        Objects.requireNonNull(maxWallClock, "maxWallClock");
        if (maxPartBytes < 1 || maxOutputChars < 1 || maxSectionDepth < 1 || maxIterations < 1) {
            throw new IllegalArgumentException("every render limit must be positive");
        }
        if (maxWallClock.isNegative() || maxWallClock.isZero()) {
            throw new IllegalArgumentException("a render must have a positive wall-clock budget");
        }
    }
}
