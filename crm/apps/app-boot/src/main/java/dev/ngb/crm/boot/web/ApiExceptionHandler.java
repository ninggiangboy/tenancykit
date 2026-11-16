package dev.ngb.crm.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.crm.webhook.application.endpoint.exception.PreconditionRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * crm's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable answer for
 * a body Jackson cannot read.
 *
 * <p>The rule a feature's exception is validated against is {@link IllegalArgumentException}: an endpoint's own
 * request rules — an unknown event type, a malformed tag, a limit exceeded — answer {@code 400} with the rule's own
 * message, which is written for the caller and carries no personal data. Later phases add their exceptions here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    /** A conditional change with no If-Match: the caller must say which version it is changing. */
    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }
}
