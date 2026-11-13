package dev.ngb.billing.invoicing.application.cycle.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.invoicing.application.cycle.port.UnbilledLineRepository;
import dev.ngb.billing.invoicing.application.cycle.service.CycleSettings;
import dev.ngb.billing.invoicing.application.invoice.service.ChargeLineFactory;
import dev.ngb.billing.invoicing.application.messaging.port.MalformedEventRecorder;
import dev.ngb.billing.invoicing.application.messaging.service.InvoicingConsumers;
import dev.ngb.billing.invoicing.application.subscription.port.SubscriptionRepository;
import dev.ngb.billing.invoicing.domain.cycle.UnbilledLine;
import dev.ngb.billing.invoicing.domain.subscription.Subscription;
import dev.ngb.billing.invoicing.domain.subscription.SubscriptionHistoryEntry;
import dev.ngb.billing.invoicing.domain.subscription.SubscriptionId;
import dev.ngb.billing.money.api.event.MoneyEventTypes;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * What the cycle consumer does with a {@code charge.succeeded}: a well-formed charge becomes one unbilled line, and
 * a payload it cannot decode is recorded once and treated as handled, so the transaction it shares with the other
 * consumers still commits.
 */
class CollectChargeUseCaseTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-12-05T10:00:00Z");

    private final WalletId walletId = new WalletId(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final UUID chargeId = UUID.randomUUID();

    private final List<UnbilledLine> lines = new ArrayList<>();
    private final List<DomainEvent> inboxed = new ArrayList<>();
    private final List<Reported> reported = new ArrayList<>();
    private WalletMode mode = WalletMode.POSTPAID;

    /** One report of a payload that could not be decoded. */
    private record Reported(String consumer, DomainEvent event, MalformedEventException malformed) {}

    private final UnitOfWork passThrough = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final EventInbox inbox = (consumer, event) -> {
        inboxed.add(event);
        return true;
    };

    private final MalformedEventRecorder recorder = (consumer, event, malformed) -> reported.add(new Reported(consumer, event, malformed));

    private final WalletLookup wallets = id -> id.equals(walletId)
            ? Optional.of(new WalletSummary(walletId, customerId, USD, mode))
            : Optional.empty();

    private final UnbilledLineRepository unbilled = new UnbilledLineRepository() {
        @Override
        public boolean add(UnbilledLine line) {
            return lines.add(line);
        }

        @Override
        public Optional<UnbilledLine> lockByCharge(UUID charge) {
            return Optional.empty();
        }

        @Override
        public boolean periodInvoiced(WalletId wallet, Instant cycleStart) {
            return false;
        }

        @Override
        public List<ClosedPeriod> closedPeriods(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<UnbilledLine> lockPeriod(WalletId wallet, Instant cycleStart) {
            return List.of();
        }
    };

    private final SubscriptionRepository subscriptions = new SubscriptionRepository() {
        @Override
        public boolean addIfAbsent(Subscription subscription) {
            return false;
        }

        @Override
        public void update(Subscription subscription) {}

        @Override
        public Optional<Subscription> findById(SubscriptionId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Subscription> lockById(SubscriptionId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Subscription> lockNextDue(Instant now, Set<UUID> excluding) {
            return Optional.empty();
        }

        @Override
        public Optional<Subscription> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public void appendEvent(SubscriptionHistoryEntry event) {}
    };

    private final CollectChargeUseCase useCase = new CollectChargeUseCase(passThrough, inbox, wallets, unbilled,
            new ChargeLineFactory(subscriptions, UUID::randomUUID), new CycleSettings(BillingCycle.MONTHLY, 10), () -> NOW, recorder);

    private static Map<String, Object> amount(long minorUnits, String currency) {
        return Map.of("minorUnits", minorUnits, "currency", currency);
    }

    /** The payload {@code charge.succeeded} carries, as {@code MoneyEvents.ChargeSucceeded} builds it. */
    private Map<String, Object> payload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chargeId", chargeId.toString());
        data.put("walletId", walletId.toString());
        data.put("amount", amount(500, "USD"));
        data.put("source", "USAGE");
        data.put("usageRef", "usage:2026-12");
        data.put("reservationId", null);
        data.put("reference", "usage/2026-12");
        return data;
    }

    private DomainEvent event(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), TENANT, MoneyEventTypes.CHARGE_SUCCEEDED, "charge/" + chargeId, NOW, data);
    }

    @Test
    void aChargeOnAPostpaidWalletBecomesOneUnbilledLine() {
        DomainEvent succeeded = event(payload());

        assertThat(useCase.handle(succeeded)).isTrue();

        assertThat(reported).isEmpty();
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.tenantId()).isEqualTo(TENANT);
            assertThat(line.walletId()).isEqualTo(walletId);
            assertThat(line.customerId()).isEqualTo(customerId);
            assertThat(line.item().chargeId()).isEqualTo(chargeId);
            assertThat(line.item().amount()).isEqualTo(Money.of(500, USD));
            assertThat(line.cycleStart()).as("the calendar month the charge was incurred in").isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
            assertThat(line.cycleEnd()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        });
    }

    @Test
    void aChargeOnAPrepaidWalletAddsNoLineAndIsNoFailure() {
        mode = WalletMode.PREPAID;

        assertThat(useCase.handle(event(payload()))).isFalse();

        assertThat(lines).isEmpty();
        assertThat(reported).as("the wallet already paid it; that is not a malformed event").isEmpty();
        assertThat(inboxed).hasSize(1);
    }

    @Test
    void aPayloadWithoutAnAmountIsReportedOnceAndTheEventIsStillHandled() {
        DomainEvent malformed = event(Map.of("chargeId", chargeId.toString(), "walletId", walletId.toString()));

        assertThat(useCase.handle(malformed)).isFalse();

        assertThat(reported).singleElement().satisfies(report -> {
            assertThat(report.consumer()).isEqualTo(InvoicingConsumers.CYCLE);
            assertThat(report.event()).isSameAs(malformed);
            assertThat(report.malformed().getMessage()).contains("amount");
        });
        assertThat(inboxed).as("the inbox mark stands, so the relay does not retry what it cannot decode").containsExactly(malformed);
        assertThat(lines).isEmpty();
    }

    @Test
    void anAmountObjectWithTheWrongShapeIsMalformed() {
        Map<String, Object> data = new LinkedHashMap<>(payload());
        data.put("amount", Map.of("minorUnits", "five hundred", "currency", "USD"));

        assertThat(useCase.handle(event(data))).isFalse();

        assertThat(reported).singleElement().satisfies(report -> assertThat(report.malformed().getMessage()).contains("minorUnits"));
        assertThat(lines).isEmpty();
    }

    @Test
    void anAmountThatIsNotAnObjectIsMalformed() {
        Map<String, Object> data = new LinkedHashMap<>(payload());
        data.put("amount", "500 USD");

        assertThat(useCase.handle(event(data))).isFalse();

        assertThat(reported).singleElement().satisfies(report -> assertThat(report.malformed().getMessage()).contains("amount"));
        assertThat(lines).isEmpty();
    }

    @Test
    void anEventOfAnotherTypeIsIgnoredBeforeAnythingHappens() {
        DomainEvent other = new EventEnvelope(UUID.randomUUID(), TENANT, "wallet.credited", "wallet/" + walletId, NOW, Map.of());

        assertThat(useCase.handle(other)).isFalse();

        assertThat(inboxed).isEmpty();
        assertThat(reported).isEmpty();
        assertThat(lines).isEmpty();
    }
}
