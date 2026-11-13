package dev.ngb.billing.money.application.charge.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerSummary;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeResult;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.api.reservation.ReservationStatus;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.application.charge.port.ChargeRepository;
import dev.ngb.billing.money.application.ledger.port.LedgerRepository;
import dev.ngb.billing.money.application.reservation.port.ReservationRepository;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.money.domain.charge.Charge;
import dev.ngb.billing.money.domain.charge.ChargeService;
import dev.ngb.billing.money.domain.event.MoneyEvents;
import dev.ngb.billing.money.domain.ledger.LedgerEntry;
import dev.ngb.billing.money.domain.reservation.Reservation;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The charge path against in-memory ports: the reservation is read once, and every outcome is a record. */
class ChargeUseCaseTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final CustomerId customerId = new CustomerId(UUID.randomUUID());

    /** The wallet holds 1 000 and the reservation holds 500 of it. */
    private final Wallet wallet = Wallet.restore(new WalletId(UUID.randomUUID()), TENANT, customerId, USD, WalletMode.PREPAID,
            Money.zero(USD), Money.of(1_000, USD), Money.of(500, USD), 1, NOW);
    private final Reservation reservation = Reservation.restore(new ReservationId(UUID.randomUUID()), TENANT, wallet.id(), Money.of(500, USD),
            Money.zero(USD), ReservationStatus.ACTIVE, NOW.plusSeconds(600), NOW, null, "plan:pro@v1", new ReplayIdentity("hold-1", "hash"));

    private final Map<WalletId, Wallet> walletsById = new LinkedHashMap<>(Map.of(wallet.id(), wallet));
    private final Map<String, Charge> chargesByKey = new LinkedHashMap<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private int reservationReads;

    private final UnitOfWork passThrough = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final WalletRepository wallets = new WalletRepository() {
        @Override
        public void add(Wallet opened) {
            walletsById.put(opened.id(), opened);
        }

        @Override
        public void update(Wallet updated) {
            walletsById.put(updated.id(), updated);
        }

        @Override
        public Optional<Wallet> findById(WalletId id) {
            return Optional.ofNullable(walletsById.get(id));
        }

        @Override
        public Optional<Wallet> lockById(WalletId id) {
            return findById(id);
        }

        @Override
        public Optional<Wallet> tryLockById(WalletId id) {
            return findById(id);
        }

        @Override
        public Optional<Wallet> findByCustomer(CustomerId customer, Currency currency) {
            return walletsById.values().stream().filter(w -> w.customerId().equals(customer) && w.currency().equals(currency)).findFirst();
        }
    };

    private final ReservationRepository reservations = new ReservationRepository() {
        @Override
        public void add(Reservation held) {}

        @Override
        public void update(Reservation updated) {}

        @Override
        public Optional<Reservation> findById(ReservationId id) {
            reservationReads++;
            return id.equals(reservation.id()) ? Optional.of(reservation) : Optional.empty();
        }

        @Override
        public Optional<Reservation> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<ReservationId> findExpired(Instant now, int limit) {
            return List.of();
        }
    };

    private final ChargeRepository charges = new ChargeRepository() {
        @Override
        public void add(Charge charge) {
            chargesByKey.put(charge.idempotencyKey(), charge);
        }

        @Override
        public Optional<Charge> findById(ChargeId id) {
            return chargesByKey.values().stream().filter(charge -> charge.id().equals(id)).findFirst();
        }

        @Override
        public Optional<Charge> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(chargesByKey.get(idempotencyKey));
        }
    };

    private final LedgerRepository ledgerRepository = new LedgerRepository() {
        @Override
        public void append(LedgerEntry entry) {
            ledger.add(entry);
        }

        @Override
        public Optional<LedgerEntry> findById(LedgerEntryId id) {
            return ledger.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
            return ledger.stream().filter(entry -> entry.origin().idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findByCharge(ChargeId chargeId) {
            return ledger.stream().filter(entry -> chargeId.equals(entry.source().chargeId())).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findReversalOf(LedgerEntryId original) {
            return Optional.empty();
        }

        @Override
        public Money balanceThrough(WalletId walletId, long sequence) {
            return Money.zero(USD);
        }
    };

    private final CustomerLookup customers = new CustomerLookup() {
        @Override
        public Optional<CustomerSummary> find(CustomerId id) {
            return Optional.of(new CustomerSummary(id, "Acme", true));
        }

        @Override
        public Optional<CustomerSummary> findFor(CustomerId id, UUID tenantSystem) {
            return find(id);
        }

        @Override
        public List<CustomerId> mappedTo(UUID tenantSystem) {
            return List.of();
        }
    };

    private final ChargeUseCase useCase = new ChargeUseCase(passThrough, wallets, reservations, charges, ledgerRepository, published::add, customers,
            UUID::randomUUID, () -> NOW);

    @Test
    void aChargeFromAReservationReadsTheReservationOnce() {
        ChargeResult result = useCase.charge(new ChargeCommand(wallet.id(), reservation.id(), Money.of(300, USD), null, "subscription/1", "charge-1"));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCEEDED);
        assertThat(result.ledgerEntryId()).isNotNull();
        assertThat(result.replayed()).isFalse();
        assertThat(reservationReads).as("one read resolves the wallet and captures that same reservation").isEqualTo(1);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CAPTURED);
        assertThat(reservation.captured()).isEqualTo(Money.of(300, USD));
        assertThat(wallet.held()).as("the whole hold was released and the charge took 300").isEqualTo(Money.zero(USD));
        assertThat(wallet.balance()).isEqualTo(Money.of(700, USD));
        assertThat(ledger).singleElement().satisfies(entry -> {
            assertThat(entry.amount()).isEqualTo(Money.of(-300, USD));
            assertThat(entry.source().reservationId()).isEqualTo(reservation.id());
        });
        assertThat(published).extracting(DomainEvent::type).containsExactly(MoneyEvents.ChargeSucceeded.TYPE);
    }

    @Test
    void aChargeWithoutAReservationReadsNoReservation() {
        ChargeResult result = useCase.charge(new ChargeCommand(wallet.id(), null, Money.of(250, USD), "usage:a,b", "usage/1", "charge-2"));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCEEDED);
        assertThat(reservationReads).isZero();
        assertThat(wallet.balance()).isEqualTo(Money.of(750, USD));
        assertThat(chargesByKey.values()).singleElement().satisfies(charge -> {
            assertThat(charge.source()).isEqualTo(ChargeSource.USAGE);
            assertThat(charge.usageRef()).isEqualTo("usage:a,b");
        });
        assertThat(ledger).hasSize(1);
        assertThat(published).extracting(DomainEvent::type).containsExactly(MoneyEvents.ChargeSucceeded.TYPE);
    }

    @Test
    void aRepeatedKeyReturnsTheRecordedChargeWithoutChargingAgain() {
        ChargeCommand command = new ChargeCommand(wallet.id(), reservation.id(), Money.of(300, USD), null, "subscription/1", "charge-3");
        ChargeResult first = useCase.charge(command);

        ChargeResult replay = useCase.charge(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.ledgerEntryId()).isEqualTo(first.ledgerEntryId());
        assertThat(ledger).as("one debit, however often the same key arrives").hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(wallet.balance()).isEqualTo(Money.of(700, USD));
    }

    @Test
    void aWalletThatCannotPayRecordsAFailedChargeInsteadOfThrowing() {
        Wallet poor = Wallet.open(new WalletId(UUID.randomUUID()), TENANT, customerId, USD, WalletMode.PREPAID, null, NOW);
        walletsById.put(poor.id(), poor);

        ChargeResult result = useCase.charge(new ChargeCommand(poor.id(), null, Money.of(250, USD), "usage:c", "usage/2", "charge-4"));

        assertThat(result.status()).isEqualTo(ChargeStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(ChargeService.INSUFFICIENT_FUNDS);
        assertThat(result.ledgerEntryId()).isNull();
        assertThat(ledger).isEmpty();
        assertThat(published).extracting(DomainEvent::type).containsExactly(MoneyEvents.ChargeFailed.TYPE);
    }
}
