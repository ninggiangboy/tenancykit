package dev.ngb.core.spring.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** One factory so every service reports the same RFC 9457 shape: status, reason-phrase title, type and detail. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    public static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
