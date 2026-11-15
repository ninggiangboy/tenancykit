package dev.ngb.mailer.email;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.mailer.email.adapter.credential.aws.AwsSenderCredentials;
import dev.ngb.mailer.email.adapter.delivery.ses.SesClientFactory;
import dev.ngb.mailer.email.adapter.delivery.ses.SesV2EmailSender;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.ResolvedSender;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.shared.Recipient;
import dev.ngb.core.id.TenantId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * The wire contract of the production adapter, driven against a local HTTP server rather than an emulator.
 *
 * <p>An emulator will not inject a 429 or a 403 on demand, and AWS Emulator does not evaluate IAM at all, so the only
 * way to prove every error branch — and the exact JSON the adapter emits — is to answer it directly. The adapter here is
 * the production one, unchanged, pointed at the server through {@code mailer.ses.endpoint-override}: that property is
 * what makes this a contract test rather than a test of a parallel implementation.
 */
class SesWireContractTest {

    private final AtomicReference<Response> nextResponse = new AtomicReference<>();
    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    private HttpServer server;
    private SesClientFactory clients;
    private SesV2EmailSender sender;

    @BeforeEach
    void startServer() throws IOException {
        // The SDK insists on signing, so it needs credentials; these never leave the process.
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        clients = new SesClientFactory("http://127.0.0.1:" + server.getAddress().getPort());
        sender = new SesV2EmailSender(clients);
    }

    @AfterEach
    void stopServer() {
        clients.close();
        server.stop(0);
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void anAcceptedSendReportsTheProviderMessageIdAndEmitsTheDocumentedRequest() {
        nextResponse.set(new Response(200, "{\"MessageId\":\"0100-abc\"}"));

        SendOutcome outcome = sender.send(email("someone@example.com", "Reply <reply@example.com>"));

        assertThat(outcome).isInstanceOf(SendOutcome.Accepted.class);
        assertThat(((SendOutcome.Accepted) outcome).messageId()).isEqualTo("0100-abc");

        String body = lastRequest.get();
        assertThat(body).contains("\"FromEmailAddress\":\"TenancyKit <noreply@example.com>\"");
        assertThat(body).contains("someone@example.com");
        assertThat(body).contains("\"Subject\"");
        assertThat(body).contains("Your invoice");
        assertThat(body).contains("reply@example.com");
    }

    @Test
    void allowlistedHeadersReachTheSesRequest() {
        nextResponse.set(new Response(200, "{\"MessageId\":\"0100-abc\"}"));

        sender.send(email("someone@example.com", null, java.util.Map.of(
                "List-Unsubscribe", "<https://example.test/email/t/u/abc>",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));

        String body = lastRequest.get();
        assertThat(body).contains("\"Headers\"");
        assertThat(body).contains("\"Name\":\"List-Unsubscribe\"");
        assertThat(body).contains("\"Value\":\"<https://example.test/email/t/u/abc>\"");
        assertThat(body).contains("\"Name\":\"List-Unsubscribe-Post\"");
    }

    @Test
    void aForbiddenResponseIsRejected() {
        nextResponse.set(new Response(403, "{\"__type\":\"AccessDeniedException\",\"message\":\"not authorized\"}"));

        assertThat(sender.send(email("someone@example.com", null))).isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void aThrottledResponseIsTransient() {
        nextResponse.set(new Response(429, "{\"__type\":\"TooManyRequestsException\",\"message\":\"slow down\"}"));

        assertThat(sender.send(email("someone@example.com", null))).isInstanceOf(SendOutcome.Transient.class);
    }

    @Test
    void aRejectedMessageIsTerminal() {
        nextResponse.set(new Response(400, "{\"__type\":\"MessageRejected\",\"message\":\"bad recipient\"}"));

        assertThat(sender.send(email("someone@example.com", null))).isInstanceOf(SendOutcome.Rejected.class);
    }

    @Test
    void aServerErrorIsTransient() {
        nextResponse.set(new Response(500, "{\"__type\":\"InternalFailure\",\"message\":\"boom\"}"));

        assertThat(sender.send(email("someone@example.com", null))).isInstanceOf(SendOutcome.Transient.class);
    }

    // --------------------------------------------------------------- helpers

    private void handle(HttpExchange exchange) throws IOException {
        lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Response response = nextResponse.get();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-amzn-RequestId", UUID.randomUUID().toString());
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static OutgoingEmail email(String recipient, String replyTo) {
        return email(recipient, replyTo, java.util.Map.of());
    }

    private static OutgoingEmail email(String recipient, String replyTo, java.util.Map<String, String> headers) {
        ResolvedSender resolved = new ResolvedSender(
                "eu-west-1",
                "noreply@example.com",
                "TenancyKit",
                replyTo,
                "test",
                new AwsSenderCredentials(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key"))));
        return new OutgoingEmail(
                new TenantId(UUID.randomUUID()),
                new Recipient(recipient),
                List.of(),
                resolved,
                "Your invoice",
                "<p>Hello</p>",
                "Hello",
                headers);
    }

    private record Response(int status, String body) {}
}
