package dev.ngb.tenant.webhook.application.event.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.resource.api.execution.ExecutionOrders;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.tenant.webhook.api.event.WebhookBodyParser;
import dev.ngb.tenant.webhook.api.event.WebhookDecision;
import dev.ngb.tenant.webhook.api.event.WebhookDeliveryStatus;
import dev.ngb.tenant.webhook.api.event.WebhookEnvelope;
import dev.ngb.tenant.webhook.application.event.port.CustomerPeriods;
import dev.ngb.tenant.webhook.application.event.port.DebtBook;
import dev.ngb.tenant.webhook.application.event.port.WebhookRepository;
import dev.ngb.tenant.webhook.application.event.service.WebhookPolicy;
import dev.ngb.tenant.webhook.domain.event.WebhookEvent;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ReceiveWebhookUseCaseTest {

    private static final String SECRET = "whsec_test";
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String TIMESTAMP = Long.toString(NOW.getEpochSecond());

    private final FakeEvents events = new FakeEvents();
    private final FakeDebts debts = new FakeDebts();
    private final FakePeriods periods = new FakePeriods();
    private final FakeOrders orders = new FakeOrders();
    private final WebhookPolicy policy = new WebhookPolicy(debts, periods, orders, () -> NOW);
    private final UnitOfWork unitOfWork = new InMemoryUnitOfWork(events, debts);
    private final ReceiveWebhookUseCase receive = new ReceiveWebhookUseCase(
            events, policy, unitOfWork, () -> NOW, () -> UUID.randomUUID(), SECRET, Duration.ofMinutes(5));

    @Test
    void acceptsAFreshDeliveryAndAppliesThePolicy() {
        WebhookEnvelope envelope = paymentFailed();

        ReceiveWebhookUseCase.Outcome outcome = receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));

        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.decision()).isEqualTo(WebhookDecision.RECORD_DEBT);
        assertThat(outcome.envelope()).isEqualTo(envelope);
        assertThat(debts.recorded).hasSize(1);
        assertThat(events.byId.get("evt_1").status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
    }

    @Test
    void aDuplicateDeliveryAppliesThePolicyOnce() {
        WebhookEnvelope envelope = paymentFailed();

        receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));
        ReceiveWebhookUseCase.Outcome second =
                receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));

        assertThat(second.duplicate()).isTrue();
        assertThat(debts.recorded).hasSize(1);
    }

    @Test
    void rejectsAForgedSignatureWithoutParsingTheBody() {
        WebhookEnvelope envelope = paymentFailed();
        ReceiveWebhookUseCase.Headers headers =
                new ReceiveWebhookUseCase.Headers("evt_1", envelope.type(), TIMESTAMP, "v1=deadbeef");

        assertThatThrownBy(() -> receive.receive(headers, "{\"id\":".getBytes(StandardCharsets.UTF_8), neverParsed()))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(events.byId).isEmpty();
        assertThat(debts.recorded).isEmpty();
    }

    @Test
    void rejectsAStaleTimestamp() {
        WebhookEnvelope envelope = paymentFailed();
        String stale = Long.toString(NOW.minusSeconds(600).getEpochSecond());

        assertThatThrownBy(() -> receive.receive(headers(envelope, stale), body(), parser(envelope)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsAnEventIdHeaderThatDoesNotMatchTheBody() {
        WebhookEnvelope envelope = paymentFailed();
        String signature = sign(SECRET, TIMESTAMP, body());
        ReceiveWebhookUseCase.Headers headers =
                new ReceiveWebhookUseCase.Headers("evt_other", envelope.type(), TIMESTAMP, signature);

        assertThatThrownBy(() -> receive.receive(headers, body(), parser(envelope)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aFailingPolicyLeavesNoPartialState() {
        WebhookEnvelope envelope = paymentFailed();
        debts.fail = true;

        assertThatThrownBy(() -> receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(debts.recorded).isEmpty();
        assertThat(events.byId.get("evt_1").status()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    void aFailedSubscriptionPeriodSuspendsResources() {
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_2", "subscription.payment_failed", "tenant-1", NOW, Map.of("customerId", "cus_1"));

        ReceiveWebhookUseCase.Outcome outcome = receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));

        assertThat(outcome.decision()).isEqualTo(WebhookDecision.SUSPEND);
        assertThat(orders.suspended).contains("cus_1");
    }

    @Test
    void aRenewalResumesResourcesAndExtendsThePeriod() {
        Instant periodEnd = NOW.plus(Duration.ofDays(30));
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_3", "subscription.renewed", "tenant-1", NOW,
                Map.of("customerId", "cus_1", "periodEnd", periodEnd.toString()));

        ReceiveWebhookUseCase.Outcome outcome = receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));

        assertThat(outcome.decision()).isEqualTo(WebhookDecision.EXTEND_PERIOD);
        assertThat(orders.resumed).contains("cus_1");
        assertThat(periods.byCustomer).containsEntry("cus_1", periodEnd);
    }

    @Test
    void anUnhandledEventTypeContinues() {
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_4", "invoice.created", "tenant-1", NOW, Map.of("customerId", "cus_1"));

        ReceiveWebhookUseCase.Outcome outcome = receive.receive(headers(envelope, TIMESTAMP), body(), parser(envelope));

        assertThat(outcome.decision()).isEqualTo(WebhookDecision.CONTINUE);
    }

    private static WebhookEnvelope paymentFailed() {
        return new WebhookEnvelope(
                "evt_1", "payment.failed", "tenant-1", NOW,
                Map.of("customerId", "cus_1", "amount", Map.of("minorUnits", 500, "currency", "USD"), "reason", "declined"));
    }

    private static byte[] body() {
        return "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    }

    private static WebhookBodyParser parser(WebhookEnvelope envelope) {
        return rawBody -> envelope;
    }

    private static WebhookBodyParser neverParsed() {
        return rawBody -> {
            throw new AssertionError("a body whose signature did not verify must never be parsed");
        };
    }

    private static ReceiveWebhookUseCase.Headers headers(WebhookEnvelope envelope, String timestamp) {
        return new ReceiveWebhookUseCase.Headers(
                envelope.id(), envelope.type(), timestamp, sign(SECRET, timestamp, body()));
    }

    private static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryUnitOfWork implements UnitOfWork {

        private final FakeEvents events;
        private final FakeDebts debts;

        private InMemoryUnitOfWork(FakeEvents events, FakeDebts debts) {
            this.events = events;
            this.debts = debts;
        }

        @Override
        public <T> T transactional(Supplier<T> work) {
            Map<String, WebhookEvent> eventsSnapshot = new LinkedHashMap<>(events.byId);
            List<String> debtsSnapshot = List.copyOf(debts.recorded);
            try {
                return work.get();
            } catch (RuntimeException e) {
                events.byId.clear();
                events.byId.putAll(eventsSnapshot);
                debts.recorded.clear();
                debts.recorded.addAll(debtsSnapshot);
                throw e;
            }
        }
    }

    private static final class FakeEvents implements WebhookRepository {
        private final Map<String, WebhookEvent> byId = new LinkedHashMap<>();

        @Override
        public boolean saveIfAbsent(WebhookEvent event) {
            return byId.putIfAbsent(event.billingEventId(), event) == null;
        }

        @Override
        public Optional<WebhookEvent> findByBillingEventId(String billingEventId) {
            return Optional.ofNullable(byId.get(billingEventId));
        }

        @Override
        public void save(WebhookEvent event) {
            byId.put(event.billingEventId(), event);
        }
    }

    private static final class FakeDebts implements DebtBook {
        private final List<String> recorded = new ArrayList<>();
        private boolean fail;

        @Override
        public void recordDebt(String billingCustomerId, long minorUnits, String currency, String reason, Instant occurredAt) {
            recorded.add(billingCustomerId + ":" + minorUnits + ":" + currency);
            if (fail) {
                throw new IllegalStateException("the debt book is down");
            }
        }

        @Override
        public void clearOpenDebts(String billingCustomerId, Instant clearedAt) {}
    }

    private static final class FakePeriods implements CustomerPeriods {
        private final Map<String, Instant> byCustomer = new LinkedHashMap<>();

        @Override
        public void extend(String billingCustomerId, Instant periodEnd, Instant now) {
            byCustomer.put(billingCustomerId, periodEnd);
        }
    }

    private static final class FakeOrders implements ExecutionOrders {
        private final List<String> suspended = new ArrayList<>();
        private final List<String> resumed = new ArrayList<>();

        @Override
        public Optional<ExecutionOrder> find(UUID orderId) {
            return Optional.empty();
        }

        @Override
        public void markRunning(UUID orderId) {}

        @Override
        public void markQueued(UUID orderId) {}

        @Override
        public void markCompleted(UUID orderId) {}

        @Override
        public void markFailed(UUID orderId, String reason) {}

        @Override
        public int suspendByBillingCustomer(String billingCustomerId, Instant now) {
            suspended.add(billingCustomerId);
            return 1;
        }

        @Override
        public int resumeByBillingCustomer(String billingCustomerId, Instant now) {
            resumed.add(billingCustomerId);
            return 1;
        }
    }
}
