package dev.ngb.core.feign;

import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.error.UpstreamUnavailableException;

/**
 * The exceptions a shared client raises for an upstream failure. The module never throws one service's error type, so
 * a caller passes its own mapping; {@link #core()} is the neutral one a service without a domain error model uses.
 */
public interface FeignFailures {

    /** The upstream could not be reached, or kept failing after its retries. */
    RuntimeException unavailable(String message, Throwable cause);

    /** The same failure when there is no cause worth carrying. */
    default RuntimeException unavailable(String message) {
        return unavailable(message, null);
    }

    /** This deployment holds no usable credential for the upstream. */
    RuntimeException refused(String message);

    /** The mapping onto {@code core-kernel}'s errors, which every service build already sees. */
    static FeignFailures core() {
        return CoreFailures.INSTANCE;
    }

    /** The neutral mapping; a service with its own error model implements this interface instead. */
    enum CoreFailures implements FeignFailures {
        INSTANCE;

        @Override
        public RuntimeException unavailable(String message, Throwable cause) {
            return new UpstreamUnavailableException(message, cause);
        }

        @Override
        public RuntimeException refused(String message) {
            return new UnauthorizedException(message);
        }
    }
}
