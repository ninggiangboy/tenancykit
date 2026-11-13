package dev.ngb.billing.boot.web;

import dev.ngb.billing.money.api.wallet.InsufficientFundsException;
import dev.ngb.billing.payment.application.stripe.exception.InvalidSignatureException;
import dev.ngb.billing.payment.application.stripe.exception.ProviderRejectedException;
import dev.ngb.billing.payment.application.stripe.exception.ProviderUnavailableException;
import dev.ngb.billing.session.application.portal.exception.InvalidPortalSessionException;
import dev.ngb.core.spring.web.CoreExceptionHandlers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Billing-specific failures on top of {@link CoreExceptionHandlers}: payment, provider and connection-pool errors. */
@RestControllerAdvice
class ApiExceptionHandler extends CoreExceptionHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "The wallet cannot cover this amount.");
    }

    /** The body did not match its signature. Says nothing about why, so a caller cannot probe for the secret. */
    @ExceptionHandler(InvalidSignatureException.class)
    ProblemDetail invalidSignature(InvalidSignatureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The signature could not be verified.");
    }

    @ExceptionHandler(ProviderRejectedException.class)
    ProblemDetail providerRejected(ProviderRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** The payment provider is unreachable; the outcome is unknown, so repeating the request with the same Idempotency-Key is safe. */
    @ExceptionHandler(ProviderUnavailableException.class)
    ProblemDetail providerUnavailable(ProviderUnavailableException e) {
        LOG.warn("payment provider unavailable: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The payment provider could not be reached. Repeat the request with the same Idempotency-Key.");
    }

    /** An unknown, already used, revoked or expired session token; the answer does not say which. */
    @ExceptionHandler(InvalidPortalSessionException.class)
    ProblemDetail invalidPortalSession(InvalidPortalSessionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /**
     * Hikari ran out of connections and the request gave up waiting. The pool is bounded on purpose, so the
     * answer is a fast refusal rather than a 30s queue; every money operation carries an idempotency key, so a
     * repeat is safe. The pool's internals stay in the log.
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    ResponseEntity<ProblemDetail> noConnection(CannotGetJdbcConnectionException e) {
        LOG.warn("no database connection was available: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The request could not be completed just now. Repeat it with the same Idempotency-Key.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    /** Money arithmetic never wraps; an amount that does not fit in 64-bit minor units is a bad request. */
    @ExceptionHandler(ArithmeticException.class)
    ProblemDetail outOfRange(ArithmeticException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The amount is out of range.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof ServletRequestBindingException ? "A required header or parameter is missing." : "The request body is not valid.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
