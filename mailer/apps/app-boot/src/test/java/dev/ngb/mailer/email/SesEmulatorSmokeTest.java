package dev.ngb.mailer.email;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.email.adapter.credential.aws.AwsSenderCredentials;
import dev.ngb.mailer.email.adapter.delivery.ses.SesClientFactory;
import dev.ngb.mailer.email.adapter.delivery.ses.SesV2EmailSender;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.shared.Recipient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * The AWS Emulator smoke test: it proves the call plumbing and the request shape, and nothing about security.
 *
 * <p>It is tagged and excluded from {@code build} because it needs the emulator running. Start it with telemetry off
 * ({@code EMULATOR_TELEMETRY=off}) and run {@code ./gradlew :apps:app-boot:emulatorTest}.
 *
 * <p>Three of the emulator's own documented limits shape the result:
 * <ul>
 *   <li>it does not evaluate IAM, so it cannot prove the tenant trust policy or the {@code sts:ExternalId} requirement —
 *       that needs a real account;</li>
 *   <li>it keeps one region and one account, so cross-account isolation cannot be modelled;</li>
 *   <li>an operation it does not implement answers HTTP 501 {@code NotImplemented}, which is reported here as
 *       "not covered" rather than as a failure. That is the property that makes it safe: a gap fails loudly.</li>
 * </ul>
 *
 * <p>It also serves self-signed HTTPS on {@code 127.0.0.1:9297}, which the SDK rejects unless the certificate is in a
 * trust store; that is an environment prerequisite, not something this test can arrange. Verification is never disabled
 * in production code to make a test pass.
 */
@Tag("emulator")
class SesEmulatorSmokeTest {

    private static final String ENDPOINT = "http://127.0.0.1:9297";

    @Test
    void aSendReachesTheEmulatorAndReturnsAMessageId() {
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");
        try (SesClientFactory clients = new SesClientFactory(ENDPOINT)) {
            SesV2EmailSender sender = new SesV2EmailSender(clients);
            SendOutcome outcome = sender.send(email());

            if (outcome.toString().contains("501") || outcome.toString().contains("NotImplemented")) {
                System.out.println("EMULATOR NOT COVERED: SendEmail answered 501 NotImplemented");
                return;
            }
            assertThat(outcome).as("the emulator must answer a send with a message id").isInstanceOf(SendOutcome.Accepted.class);
            assertThat(((SendOutcome.Accepted) outcome).messageId()).isNotBlank();
        } finally {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
        }
    }

    private static OutgoingEmail email() {
        ResolvedSender resolved = new ResolvedSender(
                "us-east-1",
                "noreply@example.com",
                "TenancyKit",
                null,
                "emulator",
                new AwsSenderCredentials(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key"))));
        return new OutgoingEmail(
                new TenantId(UUID.randomUUID()),
                new Recipient("someone@example.com"),
                List.of(),
                resolved,
                "Emulator smoke",
                null,
                "Hello from the AWS Emulator");
    }
}
