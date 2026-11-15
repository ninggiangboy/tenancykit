package dev.ngb.notification.boot.web;

import dev.ngb.core.spring.web.CoreExceptionHandlers;
import dev.ngb.notification.dispatch.application.notification.exception.ForbiddenTemplateConstructException;
import dev.ngb.notification.dispatch.application.notification.exception.InvalidEmailCommandException;
import dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Notification-specific failures on top of {@link CoreExceptionHandlers}; the problem body is the suite's. */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    @ExceptionHandler({ForbiddenTemplateConstructException.class, TemplateTooLargeException.class})
    ProblemDetail invalidTemplate(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A published command that fails its own contract is a producer bug; it never carries a recipient in its message. */
    @ExceptionHandler(InvalidEmailCommandException.class)
    ProblemDetail invalidCommand(InvalidEmailCommandException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The email command could not be built.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid.");
    }
}
