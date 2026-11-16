package dev.ngb.loyalty.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.core.spring.web.ProblemDetails;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, a readable answer
 * for a body Jackson cannot read, and the program's own refusals under the code its API document names.
 *
 * <p>Feature-specific exceptions join this class as their areas arrive. None of them may carry a member's reference or
 * an amount in its message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }

    /** A program, tier or promotion refusal: the code travels in the body so a caller can branch on it. */
    @ExceptionHandler(ProgramRefusal.class)
    ResponseEntity<ProblemDetail> programRefusal(ProgramRefusal e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
        };
        ProblemDetail detail = ProblemDetails.of(status, e.getMessage());
        detail.setProperty("code", e.code());
        return ResponseEntity.status(status).body(detail);
    }
}
