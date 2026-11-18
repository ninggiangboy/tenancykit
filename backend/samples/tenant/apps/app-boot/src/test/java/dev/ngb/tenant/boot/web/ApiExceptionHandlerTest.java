package dev.ngb.tenant.boot.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.error.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsTheTenantSpecificFailures() {
        assertStatus(handler.unauthorized(new UnauthorizedException("no usable credential")), 401);
        assertStatus(handler.upstream(new UpstreamUnavailableException("billing down")), 502);
    }

    @Test
    void inheritsTheCoreNotFoundMapping() {
        assertStatus(handler.notFound(new NotFoundException("no order")), 404);
    }

    private static void assertStatus(ProblemDetail problem, int status) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isNotBlank();
    }
}
