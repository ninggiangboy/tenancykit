package dev.ngb.platform.boot.web;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.entitlement.exception.UnknownAppException;
import dev.ngb.platform.tenancy.application.tenant.exception.HostnameAlreadyMappedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantCodeAlreadyUsedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns application errors into RFC 9457 problem responses. Unexpected errors reveal nothing about the cause. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({NotFoundException.class, TenantNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ConflictException.class, TenantCodeAlreadyUsedException.class, HostnameAlreadyMappedException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** The caller compared against an older state; the current ETag rides along so it can retry without a GET. */
    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<ProblemDetail> preconditionFailed(PreconditionFailedException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.PRECONDITION_FAILED, "The resource changed since it was read. Fetch it again and retry with the new ETag.");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.PRECONDITION_FAILED);
        if (e.currentVersion() != null) {
            response.eTag(ExpectedVersion.etag(e.currentVersion()));
        }
        return response.body(detail);
    }

    @ExceptionHandler(UnknownAppException.class)
    ProblemDetail unknownApp(UnknownAppException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        LOG.warn("data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request refers to something that does not exist here, or clashes with existing data.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission to do this.");
    }

    /**
     * A deadlock ({@code 40P01}) or a lock timeout ({@code 55P03}) is temporary: the transaction was the victim or
     * gave up waiting. Admin writes are safe to repeat, so the caller is told to retry rather than shown a 500.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> retryableLock(PessimisticLockingFailureException e) {
        LOG.warn("a transaction could not take its lock: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The request could not be completed just now. Repeat it.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ProblemDetail unreadable(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request could not be read.");
    }
}
