package dev.ngb.mailroom.content.adapter.render.mustache;

import dev.ngb.mailroom.content.api.render.TemplateLimitExceededException;
import dev.ngb.mailroom.content.domain.template.RenderLimits;

/**
 * The per-render watchdog. A library with no resource limits cannot be trusted to enforce its own, so the guard is
 * carried by the model and the writer: every loop iteration and every write checks it, and a render that breaks a
 * limit fails instead of being truncated or left to run.
 */
final class RenderBudget {

    private final RenderLimits limits;
    private final long deadlineNanos;
    private int iterations;

    RenderBudget(RenderLimits limits) {
        this.limits = limits;
        this.deadlineNanos = System.nanoTime() + limits.maxWallClock().toNanos();
    }

    void iteration() {
        iterations++;
        if (iterations > limits.maxIterations()) {
            throw new TemplateLimitExceededException("iteration count");
        }
        checkClock();
    }

    void checkClock() {
        if (System.nanoTime() > deadlineNanos) {
            throw new TemplateLimitExceededException("wall clock");
        }
    }

    int maxOutputChars() {
        return limits.maxOutputChars();
    }
}
