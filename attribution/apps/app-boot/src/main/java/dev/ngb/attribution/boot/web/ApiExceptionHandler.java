package dev.ngb.attribution.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable
 * answer for a body Jackson cannot read.
 *
 * <p>Feature-specific exceptions join this class as their phases arrive. None of them may carry a destination query, a
 * click token or a password hash in its message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }
}
