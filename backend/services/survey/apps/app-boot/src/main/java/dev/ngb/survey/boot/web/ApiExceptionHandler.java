package dev.ngb.survey.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.survey.authoring.adapter.document.web.ValidationResult;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.collection.application.link.exception.LinkRefusedException;
import dev.ngb.survey.shared.PreconditionRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, a readable
 * answer for a body Jackson cannot read, the validation report a builder branches on, and the {@code 428} a
 * conditional endpoint answers when it is called without its tag.
 *
 * <p>Feature-specific exceptions join this class as their phases arrive. None of them may carry an answer, an address
 * or a token in its message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }

    /** The document would not publish as sent; every offending pointer rides along. */
    @ExceptionHandler(DraftInvalidException.class)
    ResponseEntity<ValidationResult> invalidDocument(DraftInvalidException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ValidationResult.of(e.errors()));
    }

    /**
     * A link the suite refuses to issue. A source that already has its public link, or a survey that is not open, is
     * a clash with the survey's current state ({@code 409}); a recipient named twice is a body that cannot be valid
     * ({@code 422}). The message never carries the source or the reference, so it is safe to return.
     */
    @ExceptionHandler(LinkRefusedException.class)
    ProblemDetail linkRefused(LinkRefusedException e) {
        HttpStatus status = e.reason() == LinkRefusedException.Reason.DUPLICATE_REFERENCE
                ? HttpStatus.UNPROCESSABLE_CONTENT
                : HttpStatus.CONFLICT;
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    /**
     * The caller sent no {@code If-Match}: {@code 428}, not {@code 412}. Treating a missing tag as "whatever is
     * there" would make every save and publish last-write-wins, which is exactly what the tag exists to prevent.
     */
    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.PRECONDITION_REQUIRED,
                "This endpoint requires an If-Match header carrying the tag it was read at.");
    }
}
