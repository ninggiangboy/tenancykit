package dev.ngb.workflow.boot.web;

import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.workflow.definitions.application.definition.exception.DefinitionInvalidException;
import dev.ngb.workflow.definitions.application.definition.exception.PreconditionRequiredException;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP edge's problem bodies on top of {@link CoreExceptionHandlers}: the suite's one error shape, plus a readable
 * answer for a body Jackson cannot read, the 422 of a draft that cannot be published, and the 428 of a conditional
 * change that did not say what it was based on.
 *
 * <p>Feature-specific exceptions join this class as their phases arrive. None of them may carry a secret or an input
 * payload in its message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }

    /** The draft is unreadable, not unacceptable: every reason rides along so the author fixes them in one round. */
    @ExceptionHandler(DefinitionInvalidException.class)
    ProblemDetail definitionInvalid(DefinitionInvalidException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), e.getMessage());
        List<Map<String, String>> problems = e.problems().stream()
                .map(ApiExceptionHandler::problem)
                .toList();
        detail.setProperty("problems", problems);
        return detail;
    }

    /** A delivery the trigger's secret does not prove, or a credential this deployment does not hold. */
    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail unauthorized(UnauthorizedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(401), e.getMessage());
    }

    /** A conditional change without its version says nothing about the state it was decided on. */
    @ExceptionHandler({
        PreconditionRequiredException.class,
        dev.ngb.workflow.triggers.application.trigger.exception.PreconditionRequiredException.class
    })
    ProblemDetail preconditionRequired(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(428), e.getMessage());
    }

    private static Map<String, String> problem(Problem problem) {
        return Map.of("pointer", problem.pointer(), "code", problem.code(), "message", problem.message());
    }
}
