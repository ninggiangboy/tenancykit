package dev.ngb.mailer.email.adapter.delivery.ses;

import dev.ngb.mailer.email.application.email.port.SendOutcome;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Turns an SES failure into the ledger's three-way answer.
 *
 * <p>It is keyed on the HTTP status and the error code, in that order, and only then on the exception class. That order
 * matters for one case that a class-based mapping gets wrong: {@code AccessDenied} is not modelled as its own exception
 * for {@code SendEmail} — it arrives as the base {@link SesV2Exception} with a 403 — so mapping by class alone would
 * treat a revoked role as transient and retry it until the budget ran out.
 */
public final class SesErrorTranslator {

    private SesErrorTranslator() {}

    public static SendOutcome translate(SesV2Exception failure) {
        int status = failure.statusCode();
        String code = failure.awsErrorDetails() == null ? "" : failure.awsErrorDetails().errorCode();
        String detail = code + ": " + failure.getMessage();
        if (status == 403 || "AccessDeniedException".equals(code) || "AccessDenied".equals(code)) {
            // Credentials or IAM are wrong. Retrying cannot fix a policy, and each attempt burns quota.
            return new SendOutcome.Rejected(detail);
        }
        if (status == 429 || status >= 500 || failure.isThrottlingException() || failure.isRetryableException()) {
            return new SendOutcome.Transient(detail);
        }
        if ("MessageRejected".equals(code)
                || "MessageRejectedException".equals(code)
                || "MailFromDomainNotVerified".equals(code)
                || "MailFromDomainNotVerifiedException".equals(code)
                || "SendingPaused".equals(code)
                || "SendingPausedException".equals(code)
                || "AccountSuspended".equals(code)
                || "AccountSuspendedException".equals(code)) {
            return new SendOutcome.Rejected(detail);
        }
        if (status >= 400) {
            // Any other 4xx — a bad request, an unknown identity — is the request's fault.
            return new SendOutcome.Rejected(detail);
        }
        // A response we cannot classify is retried: an unknown 2xx/3xx on a send is more likely a proxy than a refusal.
        return new SendOutcome.Transient(detail);
    }

    public static SendOutcome translateUnexpected(RuntimeException failure) {
        if (failure instanceof SdkClientException) {
            // Transport, TLS or a timeout: a condition that may pass.
            return new SendOutcome.Transient("the SES request did not complete: " + failure.getMessage());
        }
        return new SendOutcome.Transient(failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
}
