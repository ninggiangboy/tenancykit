package dev.ngb.booking.gate.adapter.publicgate.web;

import dev.ngb.booking.gate.application.exception.GateException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** The public edge answers in the suite's problem shape, with the code the browser branches on. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class PublicProblemHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> missing(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.NOT_FOUND, "Not found."));
    }

    @ExceptionHandler(GateException.class)
    ResponseEntity<ProblemDetail> gate(GateException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.valueOf(e.status()), e.getMessage());
        detail.setProperty("code", e.code());
        if (e.retryAfterSeconds() != null) {
            return ResponseEntity.status(e.status())
                    .header("Retry-After", e.retryAfterSeconds().toString())
                    .body(detail);
        }
        return ResponseEntity.status(e.status()).body(detail);
    }
}
