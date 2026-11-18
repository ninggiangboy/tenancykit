package dev.ngb.tenant.boot.web;

import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.error.UpstreamUnavailableException;
import dev.ngb.core.spring.web.CoreExceptionHandlers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Service-specific failures on top of {@link CoreExceptionHandlers}: the two errors only the tenant maps. */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail unauthorized(UnauthorizedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ProblemDetail upstream(UpstreamUnavailableException e) {
        LOG.warn("billing is unavailable: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Billing could not be reached. The request is safe to repeat.");
    }
}
