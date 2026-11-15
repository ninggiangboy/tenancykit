package dev.ngb.mailer.email;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.mailer.email.adapter.delivery.ses.SesErrorTranslator;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Every branch of the SES mapping table, including the one a class-based mapping gets wrong.
 *
 * <p>{@code AccessDenied} is not modelled as its own exception for {@code SendEmail}: it arrives as the base
 * {@link SesV2Exception} with a 403. A translator that switched on the exception type would treat a revoked role as
 * transient, retry it until the budget ran out, and hide the real cause behind a wall of {@code DEAD} rows.
 */
class SesErrorTranslationTest {

    @Test
    void aForbiddenResponseIsRejectedEvenOnTheBaseExceptionType() {
        SendOutcome outcome = SesErrorTranslator.translate(serviceException(403, "AccessDeniedException"));

        assertThat(outcome).isInstanceOf(SendOutcome.Rejected.class);
        assertThat(outcome.toString()).contains("AccessDeniedException");
    }

    @Test
    void aThrottledResponseIsTransient() {
        assertThat(SesErrorTranslator.translate(serviceException(429, "TooManyRequestsException")))
                .isInstanceOf(SendOutcome.Transient.class);
    }

    @Test
    void aServerErrorIsTransient() {
        assertThat(SesErrorTranslator.translate(serviceException(500, "InternalFailure")))
                .isInstanceOf(SendOutcome.Transient.class);
    }

    @Test
    void aRejectedMessageIsTerminal() {
        assertThat(SesErrorTranslator.translate(serviceException(400, "MessageRejected")))
                .isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void anUnverifiedMailFromDomainIsTerminal() {
        assertThat(SesErrorTranslator.translate(serviceException(400, "MailFromDomainNotVerified")))
                .isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void aSuspendedAccountIsTerminal() {
        assertThat(SesErrorTranslator.translate(serviceException(400, "AccountSuspendedException")))
                .isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void anyOtherClientErrorIsTerminal() {
        assertThat(SesErrorTranslator.translate(serviceException(404, "NotFoundException")))
                .isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void aTransportFailureIsTransient() {
        assertThat(SesErrorTranslator.translateUnexpected(SdkClientException.create("connection reset")))
                .isInstanceOf(SendOutcome.Transient.class);
    }

    private static SesV2Exception serviceException(int status, String code) {
        return (SesV2Exception) SesV2Exception.builder()
                .statusCode(status)
                .message(code + " from SES")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(code)
                        .errorMessage(code + " from SES")
                        .serviceName("SESv2")
                        .build())
                .build();
    }
}
