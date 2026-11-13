package dev.ngb.core.feign;

import java.time.Duration;
import java.util.Objects;

/**
 * How one service reaches another: where it lives, how long a call may take and how many times a refused call is
 * tried.
 *
 * @param service the name used in failure messages, such as {@code Platform}
 * @param baseUrl the deployment's root URL
 * @param connectTimeout how long opening a connection may take
 * @param readTimeout how long waiting for the answer may take
 * @param maxAttempts total attempts, including the first
 * @param retryBackoff the wait before the second attempt; each later wait grows by the same amount
 */
public record FeignSettings(
        String service,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration retryBackoff) {

    public FeignSettings {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must allow at least one attempt");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
    }
}
