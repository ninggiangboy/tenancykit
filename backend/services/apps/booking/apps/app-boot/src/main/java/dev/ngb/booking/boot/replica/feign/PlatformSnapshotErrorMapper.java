package dev.ngb.booking.boot.replica.feign;

import dev.ngb.core.feign.FeignFailures;
import dev.ngb.core.feign.FeignJson;
import dev.ngb.core.feign.StatusFeignErrorMapper;

/**
 * A refused snapshot is a configuration or contract problem: a retry cannot fix a wrong secret, an unknown stream or
 * a malformed cursor, so only {@code 5xx} and {@code 429} stay retryable.
 */
final class PlatformSnapshotErrorMapper extends StatusFeignErrorMapper {

    private final FeignFailures failures;

    PlatformSnapshotErrorMapper(FeignFailures failures) {
        super("Platform", FeignJson.mapper());
        this.failures = failures;
    }

    @Override
    protected Exception refusal(int status, String detail) {
        if (status == 401 || status == 403) {
            return new IllegalStateException("Platform refused the snapshot credential: " + detail);
        }
        if (status >= 400 && status < 500) {
            return new IllegalStateException("Platform answered the snapshot with " + status + ": " + detail);
        }
        return failures.unavailable("Platform answered the snapshot with " + status + ": " + detail);
    }
}
