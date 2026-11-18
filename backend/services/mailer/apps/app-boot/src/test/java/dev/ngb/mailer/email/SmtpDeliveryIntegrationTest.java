package dev.ngb.mailer.email;

import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import dev.ngb.mailer.email.application.email.command.DeliverEmailsUseCase;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.support.FakeSmtpServer;
import dev.ngb.mailer.support.PostgresContainerConfig;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The plan's main success criterion, end to end through the real transport: a command becomes exactly one email and one
 * {@code SUCCEEDED} ledger row, and running the round again sends no second email.
 *
 * <p>The SMTP server is {@link FakeSmtpServer}, so the assertion is on the bytes JavaMail actually emitted — recipient,
 * subject, body — not on a return value. The full local pipeline against Mailpit is a compose-level check
 * ({@code docker compose up} plus {@code ops/smoke}); this test is the part that can run in a build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfig.class)
class SmtpDeliveryIntegrationTest {

    private static final FakeSmtpServer SMTP = FakeSmtpServer.start();

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", SMTP::host);
        registry.add("spring.mail.port", SMTP::port);
        registry.add("mailer.sender.kind", () -> "smtp");
        registry.add("mailer.sender.default-from-address", () -> "noreply@example.com");
        registry.add("mailer.sender.default-from-name", () -> "TenancyKit");
    }

    @AfterAll
    static void stopServer() {
        SMTP.close();
    }

    @Autowired
    AcceptEmailCommandUseCase accept;

    @Autowired
    DeliverEmailsUseCase deliver;

    @Autowired
    DSLContext dsl;

    @Autowired
    UnitOfWork unitOfWork;

    @Autowired
    TenantContext tenantContext;

    private TenantId tenant;

    @BeforeEach
    void freshTenant() {
        tenant = new TenantId(UUID.randomUUID());
        SMTP.reset();
    }

    @Test
    void oneCommandBecomesOneEmailAndOneSucceededRow() {
        String requestId = "deliver-" + UUID.randomUUID();
        accept(requestId);

        deliverOnce(requestId);

        assertThat(SMTP.messageCount()).isEqualTo(1);
        String message = SMTP.lastMessage();
        assertThat(message).contains("someone@example.com");
        assertThat(message).contains("TenancyKit");
        assertThat(message).contains("noreply@example.com");
        assertThat(message).contains("Hello from TenancyKit");
        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.SUCCEEDED.name());
    }

    @Test
    void aSucceededDeliveryWritesOneResultToTheOutbox() {
        String requestId = "outbox-" + UUID.randomUUID();
        accept(requestId);

        deliverOnce(requestId);

        var rows = scoped(() -> dsl.fetch(
                "select type, payload->>'requestId' as request_id, payload->>'status' as status "
                        + "from outbox where tenant_id = ?",
                tenant.value()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type", String.class)).isEqualTo("email-delivery.changed");
        assertThat(rows.get(0).get("request_id", String.class)).isEqualTo(requestId);
        assertThat(rows.get(0).get("status", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void allowlistedHeadersReachTheMessage() {
        String requestId = "headers-" + UUID.randomUUID();
        accept(requestId, java.util.Map.of(
                "List-Unsubscribe", "<https://example.test/email/t/u/abc>",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));

        deliverOnce(requestId);

        String message = SMTP.lastMessage();
        assertThat(message).contains("List-Unsubscribe: <https://example.test/email/t/u/abc>");
        assertThat(message).contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click");
    }

    @Test
    void runningTheRoundAgainSendsNoSecondEmail() {
        String requestId = "replay-" + UUID.randomUUID();
        accept(requestId);

        deliverOnce(requestId);
        // The completed row is not due again, so the second round claims nothing and sends nothing.
        DeliverEmailsUseCase.Round second = scoped(() -> deliver.runOnce());
        assertThat(second.claimed()).isZero();

        assertThat(SMTP.messageCount()).isEqualTo(1);
        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.SUCCEEDED.name());
    }

    // --------------------------------------------------------------- helpers

    private void accept(String requestId) {
        accept(requestId, java.util.Map.of());
    }

    private void accept(String requestId, java.util.Map<String, String> headers) {
        AcceptEmailCommandUseCase.Command command = new AcceptEmailCommandUseCase.Command(
                requestId, List.of("someone@example.com"), List.of(), "default", "TenancyKit", null,
                "Your invoice 42", null, "Hello from TenancyKit", headers);
        AcceptEmailCommandUseCase.Outcome outcome = scoped(() -> accept.accept(tenant, command));
        assertThat(outcome).isEqualTo(AcceptEmailCommandUseCase.Outcome.CREATED);
    }

    private void deliverOnce(String requestId) {
        DeliverEmailsUseCase.Round round = scoped(() -> deliver.runOnce());
        assertThat(round.claimed())
                .as("the just-accepted delivery must be due, ledger: " + rowState(requestId))
                .isEqualTo(1);
    }

    private String rowState(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.STATUS, EMAIL_DELIVERIES.ATTEMPTS, EMAIL_DELIVERIES.LAST_ERROR)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetch()
                .stream()
                .map(record -> record.get(EMAIL_DELIVERIES.STATUS) + "/" + record.get(EMAIL_DELIVERIES.ATTEMPTS) + "/"
                        + record.get(EMAIL_DELIVERIES.LAST_ERROR))
                .toList()
                .toString());
    }

    private List<String> statuses(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.STATUS)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetch(record -> record.value1()));
    }

    private <T> T scoped(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(work));
    }
}
