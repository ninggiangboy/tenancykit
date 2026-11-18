package dev.ngb.billing.money.application.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.ledger.EntryType;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.api.ledger.LedgerPostingResult;
import dev.ngb.billing.money.api.wallet.InsufficientFundsException;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.application.ledger.port.LedgerRepository;
import dev.ngb.billing.money.application.wallet.port.WalletRepository;
import dev.ngb.billing.money.domain.ledger.LedgerEntry;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Posting to the ledger against in-memory ports: the wallet hands out the sequence, the entry and the projection
 * are written together, and a retry of the same key replays the earlier entry instead of writing a second one.
 */
class LedgerPostingTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private final Wallet wallet = Wallet.restore(new WalletId(UUID.randomUUID()), TENANT, new CustomerId(UUID.randomUUID()),
            USD, WalletMode.PREPAID, Money.zero(USD), Money.of(1_000, USD), Money.zero(USD), 0, NOW);

    private final List<LedgerEntry> entries = new ArrayList<>();
    private final List<Wallet> walletWrites = new ArrayList<>();

    private final WalletRepository wallets = new WalletRepository() {
        @Override
        public void add(Wallet opened) {}

        @Override
        public void update(Wallet updated) {
            walletWrites.add(updated);
        }

        @Override
        public Optional<Wallet> findById(WalletId id) {
            return id.equals(wallet.id()) ? Optional.of(wallet) : Optional.empty();
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
        public Optional<Wallet> findByCustomer(CustomerId customerId, Currency currency) {
            return Optional.empty();
        }
    };

    private final LedgerRepository ledger = new LedgerRepository() {
        @Override
        public void append(LedgerEntry entry) {
            entries.add(entry);
        }

        @Override
        public Optional<LedgerEntry> findById(LedgerEntryId id) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
            return entries.stream().filter(entry -> entry.origin().idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findByCharge(ChargeId chargeId) {
            return entries.stream().filter(entry -> chargeId.equals(entry.source().chargeId())).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findReversalOf(LedgerEntryId original) {
            return entries.stream().filter(entry -> original.equals(entry.reversalOf())).findFirst();
        }

        @Override
        public Money balanceThrough(WalletId walletId, long sequence) {
            Money allEntries = entries.stream()
                    .filter(entry -> entry.walletId().equals(walletId))
                    .map(LedgerEntry::amount)
                    .reduce(Money.zero(USD), Money::plus);
            Money opening = wallet.balance().minus(allEntries);
            Money through = entries.stream()
                    .filter(entry -> entry.walletId().equals(walletId) && entry.sequence() <= sequence)
                    .map(LedgerEntry::amount)
                    .reduce(Money.zero(USD), Money::plus);
            return opening.plus(through);
        }
    };

    private final LedgerPosting posting = new LedgerPosting(wallets, ledger, UUID::randomUUID);

    private static LedgerEntry.Origin origin(String key) {
        return new LedgerEntry.Origin("user/test", "top-up", key);
    }

    @Test
    void aCreditAssignsTheNextSequenceAndWritesTheProjectionOnce() {
        LedgerPosting.Posted posted =
                posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);

        assertThat(posted.entry().type()).isEqualTo(EntryType.CREDIT);
        assertThat(posted.entry().amount()).isEqualTo(Money.of(250, USD));
        assertThat(posted.entry().sequence()).isEqualTo(1);
        assertThat(posted.balanceAfter()).isEqualTo(Money.of(1_250, USD));
        assertThat(posted.result().replayed()).isFalse();
        assertThat(entries).hasSize(1);
        assertThat(walletWrites).containsExactly(wallet);
    }

    @Test
    void aDebitStoresTheAmountNegatedAndLowersTheBalance() {
        LedgerPosting.Posted posted =
                posting.debit(wallet, Money.of(400, USD), LedgerEntry.Source.of("charge"), origin("k1"), NOW);

        assertThat(posted.entry().type()).isEqualTo(EntryType.DEBIT);
        assertThat(posted.entry().amount()).isEqualTo(Money.of(-400, USD));
        assertThat(posted.balanceAfter()).isEqualTo(Money.of(600, USD));
    }

    @Test
    void aDebitBeyondTheSpendableBalanceWritesNothing() {
        assertThatThrownBy(() -> posting.debit(wallet, Money.of(1_001, USD), LedgerEntry.Source.of("charge"), origin("k1"), NOW))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(entries).isEmpty();
        assertThat(walletWrites).isEmpty();
        assertThat(wallet.balance()).isEqualTo(Money.of(1_000, USD));
    }

    @Test
    void aReversalNegatesTheOriginalEntryAndRestoresTheBalance() {
        LedgerPosting.Posted original =
                posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);
        LedgerEntry.Origin reversal = new LedgerEntry.Origin("user/test", "refund", "k2");

        LedgerPosting.Posted posted = posting.reversal(wallet, original.entry(), reversal, NOW);

        assertThat(posted.entry().type()).isEqualTo(EntryType.REVERSAL);
        assertThat(posted.entry().amount()).isEqualTo(Money.of(-250, USD));
        assertThat(posted.entry().reversalOf()).isEqualTo(original.entry().id());
        assertThat(posted.balanceAfter()).isEqualTo(Money.of(1_000, USD));
    }

    @Test
    void aReversalNeedsAReason() {
        LedgerPosting.Posted original =
                posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);

        assertThatThrownBy(() ->
                        posting.reversal(wallet, original.entry(), new LedgerEntry.Origin("user/test", null, "k2"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReplayOfTheSameKeyReturnsTheEarlierEntryWithoutWriting() {
        LedgerPosting.Posted posted =
                posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);

        Optional<LedgerPostingResult> replay = posting.replay("k1", wallet, Money.of(250, USD));

        assertThat(replay).isPresent();
        assertThat(replay.get().replayed()).isTrue();
        assertThat(replay.get().entryId()).isEqualTo(posted.entry().id());
        assertThat(replay.get().sequence()).isEqualTo(1);
        assertThat(replay.get().balanceAfter()).isEqualTo(Money.of(1_250, USD));
        assertThat(entries).as("a replay writes nothing").hasSize(1);
        assertThat(walletWrites).hasSize(1);
    }

    @Test
    void anUnknownKeyIsNotAReplay() {
        assertThat(posting.replay("missing", wallet, Money.of(250, USD))).isEmpty();
    }

    @Test
    void theSameKeyForADifferentAmountIsAConflict() {
        posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);

        assertThatThrownBy(() -> posting.replay("k1", wallet, Money.of(300, USD)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void theSameKeyForAnotherWalletIsAConflict() {
        posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);
        Wallet other = Wallet.restore(new WalletId(UUID.randomUUID()), TENANT, new CustomerId(UUID.randomUUID()), USD,
                WalletMode.PREPAID, Money.zero(USD), Money.zero(USD), Money.zero(USD), 0, NOW);

        assertThatThrownBy(() -> posting.replay("k1", other, Money.of(250, USD)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void balanceThroughIncludesTheOpeningBalanceAndTheEntriesUpToTheSequence() {
        posting.credit(wallet, Money.of(250, USD), LedgerEntry.Source.of("top-up"), origin("k1"), NOW);
        posting.debit(wallet, Money.of(100, USD), LedgerEntry.Source.of("charge"), origin("k2"), NOW);

        assertThat(ledger.balanceThrough(wallet.id(), 1)).isEqualTo(Money.of(1_250, USD));
        assertThat(ledger.balanceThrough(wallet.id(), 2)).isEqualTo(Money.of(1_150, USD));
    }
}
