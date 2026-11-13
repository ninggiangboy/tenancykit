package dev.ngb.billing.money.application.reservation.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.api.reservation.ReservationStatus;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.application.reservation.port.ReservationRepository;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.money.domain.event.MoneyEvents;
import dev.ngb.billing.money.domain.reservation.Reservation;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Expiring holds against in-memory ports: a hold whose time has run out is released and announced once, a wallet
 * another worker holds is skipped and picked up on the next run, and a hold that is no longer active is left alone.
 */
class ExpireReservationsUseCaseTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private Wallet wallet;
    private final Map<ReservationId, Reservation> reservationsById = new LinkedHashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private boolean walletLocked;
    private int lastLimit;

    private final UnitOfWork passThrough = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final WalletRepository wallets = new WalletRepository() {
        @Override
        public void add(Wallet opened) {}

        @Override
        public void update(Wallet updated) {
            wallet = updated;
        }

        @Override
        public Optional<Wallet> findById(WalletId id) {
            return Optional.ofNullable(wallet).filter(w -> w.id().equals(id));
        }

        @Override
        public Optional<Wallet> lockById(WalletId id) {
            return findById(id);
        }

        @Override
        public Optional<Wallet> tryLockById(WalletId id) {
            return walletLocked ? Optional.empty() : findById(id);
        }

        @Override
        public Optional<Wallet> findByCustomer(CustomerId customerId, Currency currency) {
            return Optional.ofNullable(wallet).filter(w -> w.customerId().equals(customerId));
        }
    };

    private final ReservationRepository reservations = new ReservationRepository() {
        @Override
        public void add(Reservation reservation) {
            reservationsById.put(reservation.id(), reservation);
        }

        @Override
        public void update(Reservation updated) {
            reservationsById.put(updated.id(), updated);
        }

        @Override
        public Optional<Reservation> findById(ReservationId id) {
            return Optional.ofNullable(reservationsById.get(id));
        }

        @Override
        public Optional<Reservation> findByIdempotencyKey(String idempotencyKey) {
            return reservationsById.values().stream().filter(r -> r.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public List<ReservationId> findExpired(Instant now, int limit) {
            lastLimit = limit;
            return reservationsById.values().stream()
                    .filter(reservation -> reservation.isActive() && reservation.isExpired(now))
                    .map(Reservation::id)
                    .limit(limit)
                    .toList();
        }
    };

    private ExpireReservationsUseCase useCase() {
        return new ExpireReservationsUseCase(
                passThrough,
                wallets,
                reservations,
                published::add,
                new IdGenerator() {
                    @Override
                    public UUID newId() {
                        return UUID.randomUUID();
                    }
                },
                new Clock() {
                    @Override
                    public Instant now() {
                        return NOW;
                    }
                });
    }

    /** A wallet holding {@code held} of its 1 000, and a reservation of that hold. */
    private Reservation hold(Money amount, Instant expiresAt, ReservationStatus status) {
        wallet = Wallet.restore(new WalletId(UUID.randomUUID()), TENANT, new CustomerId(UUID.randomUUID()), USD,
                WalletMode.PREPAID, Money.zero(USD), Money.of(1_000, USD), amount, 0, NOW.minus(Duration.ofDays(1)));
        Reservation reservation = Reservation.restore(new ReservationId(UUID.randomUUID()), TENANT, wallet.id(), amount,
                Money.zero(USD), status, expiresAt, NOW.minus(Duration.ofDays(1)), null, "plan:pro@v1",
                new ReplayIdentity("hold-1", "hash"));
        reservationsById.put(reservation.id(), reservation);
        return reservation;
    }

    @Test
    void anExpiredHoldIsReleasedAndAnnouncedOnce() {
        Reservation expired = hold(Money.of(500, USD), NOW.minusSeconds(1), ReservationStatus.ACTIVE);

        int expiredCount = useCase().execute(10);

        assertThat(expiredCount).isEqualTo(1);
        assertThat(expired.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expired.closedAt()).isEqualTo(NOW);
        assertThat(wallet.held().isZero()).as("the whole hold returns to the wallet").isTrue();
        assertThat(wallet.balance()).isEqualTo(Money.of(1_000, USD));
        assertThat(published).extracting(DomainEvent::type).containsExactly(MoneyEvents.ReservationReleased.EXPIRED);
    }

    @Test
    void aHoldThatHasNotRunOutIsNotPickedUp() {
        Reservation live = hold(Money.of(500, USD), NOW.plusSeconds(60), ReservationStatus.ACTIVE);

        int expiredCount = useCase().execute(10);

        assertThat(expiredCount).isZero();
        assertThat(live.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(wallet.held()).isEqualTo(Money.of(500, USD));
        assertThat(published).isEmpty();
    }

    @Test
    void aWalletAnotherWorkerHoldsIsSkippedAndCountsAsNothing() {
        hold(Money.of(500, USD), NOW.minusSeconds(1), ReservationStatus.ACTIVE);
        walletLocked = true;

        int expiredCount = useCase().execute(10);

        assertThat(expiredCount).as("the next run picks it up once the lock is gone").isZero();
        assertThat(wallet.held()).isEqualTo(Money.of(500, USD));
        assertThat(published).isEmpty();

        walletLocked = false;
        assertThat(useCase().execute(10)).isEqualTo(1);
    }

    @Test
    void aHoldThatIsNoLongerActiveIsLeftAlone() {
        hold(Money.of(500, USD), NOW.minusSeconds(1), ReservationStatus.CAPTURED);

        assertThat(useCase().execute(10)).isZero();
        assertThat(wallet.held()).isEqualTo(Money.of(500, USD));
        assertThat(published).isEmpty();
    }

    @Test
    void theBatchSizeIsPassedToTheCandidateQuery() {
        hold(Money.of(500, USD), NOW.minusSeconds(1), ReservationStatus.ACTIVE);

        assertThat(useCase().execute(1)).isEqualTo(1);

        assertThat(lastLimit).isEqualTo(1);
    }
}
