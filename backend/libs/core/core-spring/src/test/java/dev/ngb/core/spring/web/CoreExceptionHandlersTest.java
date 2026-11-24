package dev.ngb.core.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PaymentRequiredException;
import dev.ngb.core.error.PreconditionFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class CoreExceptionHandlersTest {

    private final Handlers handlers = new Handlers();

    @Test
    void mapsTheCoreFailuresToOneStatusAndShape() {
        assertStatus(handlers.notFound(new NotFoundException("no wallet")), 404, "no wallet");
        assertStatus(handlers.conflict(new ConflictException("already there")), 409, "already there");
        assertStatus(handlers.badRequest(new BadRequestException("malformed")), 400, "malformed");
        assertStatus(handlers.paymentRequired(new PaymentRequiredException("out of funds")), 402, "out of funds");
        assertStatus(handlers.forbidden(new AccessDeniedException("no")), 403, "You do not have permission to do this.");
        assertStatus(handlers.invalid(new IllegalArgumentException("bad header")), 400, "bad header");
        assertStatus(handlers.invalidState(new IllegalStateException("broken")), 500, "Something went wrong.");
        assertStatus(handlers.integrity(new DataIntegrityViolationException("constraint")), 409,
                "The request refers to something that does not exist here, or clashes with existing data.");
        assertStatus(handlers.unexpected(new RuntimeException("boom")), 500, "Something went wrong.");
    }

    @Test
    void preconditionFailedCarriesTheCurrentVersionAsAnEtag() {
        ResponseEntity<ProblemDetail> response = handlers.preconditionFailed(new PreconditionFailedException(7L));

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"7\"");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(412);
    }

    @Test
    void aLockTimeoutIsRetryableWithARetryAfterHeader() {
        ResponseEntity<ProblemDetail> response = handlers.retryableLock(new PessimisticLockingFailureException("lock timeout"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().getStatus()).isEqualTo(503);
    }

    @Test
    void aMethodThePathDoesNotServeIs405AndNamesTheMethodsItDoes() {
        ResponseEntity<ProblemDetail> response = handlers.methodNotAllowed(
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET", "HEAD")));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD);
        assertThat(response.getBody().getStatus()).isEqualTo(405);
    }

    private static void assertStatus(ProblemDetail problem, int status, String detail) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isEqualTo(detail);
        assertThat(problem.getTitle()).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
    }

    /** A concrete advice would add {@code @RestControllerAdvice}; the inherited handlers are exercised directly here. */
    private static final class Handlers extends CoreExceptionHandlers {
    }
}
