package dev.ngb.core.spring.web;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PaymentRequiredException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.version.ExpectedVersion;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The RFC 9457 mapping shared by every service, so the same failure always produces the same status and shape.
 *
 * <p>A service exposes its own advice by extending this class and annotating the subclass with
 * {@code @RestControllerAdvice}; Spring discovers the inherited {@code @ExceptionHandler} methods too. Service-specific
 * exceptions stay in the subclass.
 */
public abstract class CoreExceptionHandlers {

    private static final System.Logger LOG = System.getLogger(CoreExceptionHandlers.class.getName());

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ConflictException.class, IdempotencyConflictException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetails.of(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PaymentRequiredException.class)
    public ProblemDetail paymentRequired(PaymentRequiredException e) {
        return ProblemDetails.of(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, "You do not have permission to do this.");
    }

    /** The caller compared against an older state; the current ETag rides along so it can retry without a GET. */
    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ProblemDetail> preconditionFailed(PreconditionFailedException e) {
        ProblemDetail detail = ProblemDetails.of(
                HttpStatus.PRECONDITION_FAILED,
                "The resource changed since it was read. Fetch it again and retry with the new ETag.");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.PRECONDITION_FAILED);
        if (e.currentVersion() != null) {
            response.eTag(ExpectedVersion.etag(e.currentVersion()));
        }
        return response.body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail invalidState(IllegalStateException e) {
        LOG.log(System.Logger.Level.ERROR, "the request reached an unexpected state", e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }

    /** A reference to something missing in this tenant, or a clash with existing data. Details stay in the log. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException e) {
        LOG.log(System.Logger.Level.WARNING, "data integrity violation: {0}", e.getMostSpecificCause().getMessage());
        return ProblemDetails.of(
                HttpStatus.CONFLICT,
                "The request refers to something that does not exist here, or clashes with existing data.");
    }

    /**
     * A deadlock ({@code 40P01}) or a lock timeout ({@code 55P03}) is temporary: the transaction was the victim or
     * gave up waiting. The caller is told to retry rather than shown an opaque 500.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> retryableLock(PessimisticLockingFailureException e) {
        LOG.log(System.Logger.Level.WARNING, "a transaction could not take its lock: {0}", e.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetails.of(
                HttpStatus.SERVICE_UNAVAILABLE, "The request could not be completed just now. Repeat it.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    /** A path no handler serves is a missing resource, not an unexpected error: {@code 404}, the same as any miss. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noResource(NoResourceFoundException e) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, "No resource exists at this path.");
    }

    /**
     * A method the path does not serve is the caller's mistake, not an unexpected error: {@code 405} with the methods it
     * does serve. A path shared by two callers can serve a different set of methods to each, so a machine reaching a
     * person-only method on a shared path lands here.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            response.allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(ProblemDetails.of(HttpStatus.METHOD_NOT_ALLOWED, "This path does not serve this method."));
    }

    /** Unexpected errors reveal nothing about the cause. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        LOG.log(System.Logger.Level.ERROR, "unexpected error", e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }
}

