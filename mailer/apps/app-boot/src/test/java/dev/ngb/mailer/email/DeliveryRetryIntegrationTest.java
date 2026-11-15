package dev.ngb.mailer.email;

import static dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.util.Backoff;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import dev.ngb.mailer.email.application.email.command.DeliverEmailsUseCase;
import dev.ngb.mailer.email.application.email.port.DeliveryClaimRepository;
import dev.ngb.mailer.email.application.email.port.EmailDeliveryRepository;
import dev.ngb.mailer.email.application.email.port.EmailSender;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.email.application.email.port.SenderResolver;
import dev.ngb.mailer.email.application.email.service.DeliverySettings;
import dev.ngb.mailer.email.domain.delivery.DeliveryPolicy;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import dev.ngb.mailer.support.PostgresContainerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The retry driver, proved where a transport cannot help: a failing send climbs the attempt count and reaches
 * {@code DEAD} at the budget, and a dead row can then be requested again and succeed.
 *
 * <p>A stopped Mailpit would be the obvious way to test this, but stopping a shared container makes every other test in
 * the JVM flaky. A sender that answers {@code Transient} exercises exactly the same ledger path, and the real transport
 * is proved separately by {@link SmtpDeliveryIntegrationTest}.
 *
 * <p>The attempt count is the point of the test: it moves in the **claim**, so a worker that dies mid-send still spends
 * its budget, and a row cannot re-send for ever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfig.class)
class DeliveryRetryIntegrationTest {

    private static final int MAX_ATTEMPTS = 2;

    @Autowired
    AcceptEmailCommandUseCase accept;
    @Autowired
    DeliveryClaimRepository claims;
    @Autowired
    EmailDeliveryRepository deliveries;
    @Autowired
    SenderResolver resolver;
    @Autowired
    DSLContext dsl;
    @Autowired
    UnitOfWork unitOfWork;
    @Autowired
    TenantContext tenantContext;
    @Autowired
    Clock clock;

    private final java.util.List<dev.ngb.mailer.email.application.email.port.DeliveryResult> published =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private TenantId tenant;

    @BeforeEach
    void freshTenant() {
        tenant = new TenantId(UUID.randomUUID());
        published.clear();
    }

    @Test
    void aTransientFailureClimbsTheBudgetAndReachesDead() {
        String requestId = "retry-" + UUID.randomUUID();
        accept(requestId, "down@example.com");
        DeliverEmailsUseCase retrying = roundFor(new SendOutcome.Transient("the SMTP server is down"));

        scoped(() -> retrying.runOnce());
        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.PENDING.name());
        assertThat(attempts(requestId)).isEqualTo(1);
        // A transient attempt is an internal step: the producer must not see a result until the budget is spent.
        assertThat(published).as("no result is published for a retryable attempt").isEmpty();

        makeDueAgain(requestId);
        scoped(() -> retrying.runOnce());

        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.DEAD.name());
        assertThat(attempts(requestId)).isEqualTo(MAX_ATTEMPTS);
        assertThat(attemptRows(requestId)).isEqualTo(MAX_ATTEMPTS);
        assertThat(published).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(DeliveryStatus.DEAD);
            assertThat(result.requestId()).isEqualTo(requestId);
            assertThat(result.attempts()).isEqualTo(MAX_ATTEMPTS);
            assertThat(result.completedAt()).isNotNull();
        });
    }

    @Test
    void aDeadDeliveryCanBeRequestedAgainAndSucceeds() {
        String requestId = "resurrect-" + UUID.randomUUID();
        accept(requestId, "ok@example.com");
        DeliverEmailsUseCase retrying = roundFor(new SendOutcome.Transient("the SMTP server is down"));

        scoped(() -> retrying.runOnce());
        makeDueAgain(requestId);
        scoped(() -> retrying.runOnce());
        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.DEAD.name());

        // The producer's retry: same request id, so the row is resurrected with a fresh budget.
        AcceptEmailCommandUseCase.Outcome outcome = scoped(() -> accept.accept(tenant, command(requestId, "ok@example.com")));
        assertThat(outcome).isEqualTo(AcceptEmailCommandUseCase.Outcome.RESURRECTED);
        assertThat(attempts(requestId)).isZero();

        scoped(() -> roundFor(new SendOutcome.Accepted("provider-1")).runOnce());

        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.SUCCEEDED.name());
        assertThat(providerMessageId(requestId)).isEqualTo("provider-1");
    }

    @Test
    void aRejectionIsTerminalOnTheFirstAttempt() {
        String requestId = "rejected-" + UUID.randomUUID();
        accept(requestId, "bad@example.com");

        scoped(() -> roundFor(new SendOutcome.Rejected("the provider refused the request")).runOnce());

        // Retrying a request the provider called wrong only burns quota, so the budget is irrelevant here.
        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.DEAD.name());
        assertThat(attempts(requestId)).isEqualTo(1);
        assertThat(published).singleElement()
                .satisfies(result -> assertThat(result.status()).isEqualTo(DeliveryStatus.DEAD));
    }

    @Test
    void aWorkerLostOnItsFinalAttemptStillReportsTheOutcome() {
        // The row a dead worker leaves behind is killed by the sweep, not by an outcome this worker wrote. The producer
        // is still owed the terminal result: without it the email it requested stays QUEUED for ever.
        String requestId = "lost-" + UUID.randomUUID();
        accept(requestId, "lost@example.com");
        loseTheWorkerDuringItsFinalAttempt(requestId);

        scoped(() -> roundFor(new SendOutcome.Accepted("unused")).runOnce());

        assertThat(statuses(requestId)).containsOnly(DeliveryStatus.DEAD.name());
        assertThat(published).singleElement().satisfies(result -> {
            assertThat(result.requestId()).isEqualTo(requestId);
            assertThat(result.status()).isEqualTo(DeliveryStatus.DEAD);
            assertThat(result.completedAt()).isNotNull();
        });
    }

    // --------------------------------------------------------------- helpers

    private DeliverEmailsUseCase roundFor(SendOutcome outcome) {
        EmailSender sender = new FixedSender(outcome);
        DeliveryPolicy policy = new DeliveryPolicy(
                MAX_ATTEMPTS,
                new Backoff(Duration.ofMillis(1), Duration.ofMillis(5), 0.0),
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));
        DeliverySettings settings = new DeliverySettings(true, 10, Duration.ofSeconds(1), policy);
        return new DeliverEmailsUseCase(
                unitOfWork, claims, resolver, sender, published::add, tenantContext, settings, clock, () -> 0.0);
    }

    private void accept(String requestId, String recipient) {
        AcceptEmailCommandUseCase.Outcome outcome = scoped(() -> accept.accept(tenant, command(requestId, recipient)));
        assertThat(outcome).isEqualTo(AcceptEmailCommandUseCase.Outcome.CREATED);
    }

    private static AcceptEmailCommandUseCase.Command command(String requestId, String recipient) {
        return new AcceptEmailCommandUseCase.Command(
                requestId, List.of(recipient), List.of(), "default", "TenancyKit", null,
                "Your invoice", null, "Hello", java.util.Map.of());
    }

    private void makeDueAgain(String requestId) {
        scoped(() -> dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.NEXT_ATTEMPT_AT, Instant.now().minusSeconds(60))
                .set(EMAIL_DELIVERIES.CLAIMED_UNTIL, (Instant) null)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .execute());
    }

    /**
     * The exact state a worker leaves when it dies mid-send on its last allowed attempt: claimed, budget spent, lease
     * expired. Nothing wrote an outcome, so only the sweep can answer for it.
     */
    private void loseTheWorkerDuringItsFinalAttempt(String requestId) {
        scoped(() -> dsl.update(EMAIL_DELIVERIES)
                .set(EMAIL_DELIVERIES.STATUS, DeliveryStatus.IN_PROGRESS.name())
                .set(EMAIL_DELIVERIES.ATTEMPTS, MAX_ATTEMPTS)
                .set(EMAIL_DELIVERIES.CLAIMED_UNTIL, Instant.now().minusSeconds(1))
                .set(EMAIL_DELIVERIES.LAST_ERROR, (String) null)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .execute());
    }

    private List<String> statuses(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.STATUS)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetch(record -> record.value1()));
    }

    private int attempts(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.ATTEMPTS)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetchAny(record -> record.value1()));
    }

    private String providerMessageId(String requestId) {
        return scoped(() -> dsl.select(EMAIL_DELIVERIES.PROVIDER_MESSAGE_ID)
                .from(EMAIL_DELIVERIES)
                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId))
                .fetchAny(record -> record.value1()));
    }

    private int attemptRows(String requestId) {
        return scoped(() -> dsl.fetchCount(
                dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERY_ATTEMPTS,
                dev.ngb.mailer_jooq.Tables.EMAIL_DELIVERY_ATTEMPTS.DELIVERY_ID.in(
                        dsl.select(EMAIL_DELIVERIES.ID)
                                .from(EMAIL_DELIVERIES)
                                .where(EMAIL_DELIVERIES.REQUEST_ID.eq(requestId)))));
    }

    private <T> T scoped(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(work));
    }

    /** A sender that answers the same way every time, so the ledger's decisions are the only variable. */
    private record FixedSender(SendOutcome outcome) implements EmailSender {

        @Override
        public SendOutcome send(OutgoingEmail email) {
            return outcome;
        }
    }
}
