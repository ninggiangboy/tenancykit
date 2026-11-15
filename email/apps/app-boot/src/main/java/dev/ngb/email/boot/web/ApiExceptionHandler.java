package dev.ngb.email.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.email.content.api.render.MissingTemplateVariableException;
import dev.ngb.email.content.api.render.TemplateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable
 * answer for a body Jackson cannot read.
 *
 * <p>Feature-specific exceptions join this class as their phases arrive. None of them may carry a recipient address or
 * a body in its message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }

    /** A render the sandbox refused: a missing variable or a broken limit is the caller's to fix, never a 500. */
    @ExceptionHandler({MissingTemplateVariableException.class, TemplateLimitExceededException.class})
    ProblemDetail renderRejected(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
