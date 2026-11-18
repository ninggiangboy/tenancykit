package dev.ngb.platform.integration.adapter.token.web;

import dev.ngb.platform.integration.application.auth.exception.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The token endpoint's refusals, in RFC 9457 form. */
@RestControllerAdvice
class TokenErrorHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail limited(RateLimitExceededException limited) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, limited.getMessage());
        detail.setProperty("retry_after", 1);
        return detail;
    }
}
