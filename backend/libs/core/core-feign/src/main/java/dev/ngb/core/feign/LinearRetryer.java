package dev.ngb.core.feign;

import feign.RetryableException;
import feign.Retryer;

/**
 * Waits {@code backoff * attempt} between tries, so a caller sees a short, predictable pause rather than a jittered
 * curve, and gives up after the configured number of attempts.
 */
public final class LinearRetryer implements Retryer {

    private final int maxAttempts;
    private final long backoffMillis;
    private final FeignFailures failures;
    private int attempt = 1;

    public LinearRetryer(int maxAttempts, long backoffMillis, FeignFailures failures) {
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
        this.failures = failures;
    }

    @Override
    public void continueOrPropagate(RetryableException e) {
        if (attempt >= maxAttempts) {
            throw e;
        }
        try {
            Thread.sleep(backoffMillis * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failures.unavailable("the retry wait was interrupted", interrupted);
        }
        attempt++;
    }

    @Override
    public Retryer clone() {
        return new LinearRetryer(maxAttempts, backoffMillis, failures);
    }
}
