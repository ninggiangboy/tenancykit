package dev.ngb.booking.boot.web;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogProblem;
import dev.ngb.booking.catalog.application.catalog.exception.DuplicateCodeException;
import dev.ngb.booking.catalog.application.catalog.exception.ResourceInUseException;
import dev.ngb.booking.catalog.application.recurrence.exception.InvalidRecurrenceException;
import dev.ngb.booking.catalog.application.session.exception.SessionInUseException;
import dev.ngb.booking.inventory.application.availability.exception.InvalidAvailabilityException;
import dev.ngb.booking.inventory.application.availability.exception.InvalidDurationException;
import dev.ngb.booking.shared.PreconditionRequiredException;
import dev.ngb.core.spring.web.CoreExceptionHandlers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, a readable
 * answer for a body Jackson cannot read, and the {@code 428} a conditional endpoint answers when it is called without
 * its tag.
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

    /**
     * The caller sent no {@code If-Match}: {@code 428}, not {@code 412}. Treating a missing tag as "whatever is
     * there" would make every save last-write-wins, which is exactly what the tag exists to prevent.
     */
    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.PRECONDITION_REQUIRED,
                "This endpoint requires an If-Match header carrying the tag it was read at.");
        detail.setProperty("code", "PRECONDITION_REQUIRED");
        return detail;
    }

    /** A catalog body that is well formed but breaks a rule: {@code 422} with the rule's stable code. */
    @ExceptionHandler(CatalogProblem.class)
    ProblemDetail catalogProblem(CatalogProblem e) {
        HttpStatus status = e instanceof ResourceInUseException
                        || e instanceof DuplicateCodeException
                        || e instanceof SessionInUseException
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setProperty("code", e.code());
        return detail;
    }

    /**
     * A rule this service will not expand: {@code 422} with the {@code invalid-recurrence} the contract names, so a
     * caller can tell a rule it sent badly from a body it sent badly.
     */
    @ExceptionHandler(InvalidRecurrenceException.class)
    ProblemDetail invalidRecurrence(InvalidRecurrenceException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        detail.setProperty("code", e.code());
        return detail;
    }

    /** A duration outside the offering's range or off its step: {@code 422} with the contract's {@code INVALID_DURATION}. */
    @ExceptionHandler(InvalidDurationException.class)
    ProblemDetail invalidDuration(InvalidDurationException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        detail.setProperty("code", e.code());
        return detail;
    }

    /** An availability request this service will not answer, such as a {@code CAPACITY} offering: {@code 422}. */
    @ExceptionHandler(InvalidAvailabilityException.class)
    ProblemDetail invalidAvailability(InvalidAvailabilityException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        detail.setProperty("code", e.code());
        return detail;
    }
}
