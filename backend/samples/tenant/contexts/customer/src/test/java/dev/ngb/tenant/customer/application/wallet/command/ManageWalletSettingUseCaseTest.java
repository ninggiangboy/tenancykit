package dev.ngb.tenant.customer.application.wallet.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.UpstreamUnavailableException;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.billing.api.model.Amount;
import dev.ngb.tenant.billing.api.model.Wallet;
import dev.ngb.tenant.customer.application.customer.command.Fakes;
import dev.ngb.tenant.customer.application.customer.service.CustomerDirectoryService;
import dev.ngb.tenant.customer.application.wallet.port.WalletSettingRepository;
import dev.ngb.tenant.customer.domain.customer.Customer;
import dev.ngb.tenant.customer.domain.wallet.CustomerWalletSetting;
import dev.ngb.tenant.customer.domain.wallet.WalletMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ManageWalletSettingUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final Fakes.Customers customers = new Fakes.Customers();
    private final RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork();
    private final InMemorySettings settings = new InMemorySettings();
    private final FakeWallets wallets = new FakeWallets(unitOfWork);
    private final IdGenerator ids = () -> UUID.randomUUID();
    private final ManageWalletSettingUseCase useCase = new ManageWalletSettingUseCase(
            new CustomerDirectoryService(customers), settings, wallets, unitOfWork, () -> NOW, "USD");

    @Test
    void theDefaultSettingIsPrepaidInTheConfiguredCurrency() {
        mapped();

        CustomerWalletSetting setting = useCase.current(USER);

        assertThat(setting.walletMode()).isEqualTo(WalletMode.PREPAID);
        assertThat(setting.currency()).isEqualTo("USD");
        assertThat(setting.walletId()).isNull();
    }

    @Test
    void theDefaultCurrencyComesFromConfiguration() {
        mapped();
        ManageWalletSettingUseCase euro = new ManageWalletSettingUseCase(
                new CustomerDirectoryService(customers), settings, wallets, unitOfWork, () -> NOW, "EUR");

        assertThat(euro.current(USER).currency()).isEqualTo("EUR");
    }

    @Test
    void choosingPostpaidOpensThatWalletOnBilling() {
        mapped();

        CustomerWalletSetting setting = useCase.update(USER, WalletMode.POSTPAID, "usd", 50_000L);

        assertThat(setting.walletMode()).isEqualTo(WalletMode.POSTPAID);
        assertThat(setting.currency()).isEqualTo("USD");
        assertThat(setting.creditLimit()).isEqualTo(50_000L);
        assertThat(setting.walletId()).isEqualTo("wal_1");
        assertThat(wallets.last.mode()).isEqualTo("POSTPAID");
        assertThat(wallets.last.creditLimit()).isEqualTo(50_000L);
        assertThat(settings.byCustomer).containsKey(setting.customerId());
        assertThat(unitOfWork.transactions).isEqualTo(1);
    }

    @Test
    void prepaidDropsAnyCreditLimit() {
        mapped();

        CustomerWalletSetting setting = useCase.update(USER, WalletMode.PREPAID, "USD", 50_000L);

        assertThat(setting.creditLimit()).isNull();
        assertThat(wallets.last.creditLimit()).isNull();
    }

    @Test
    void refusesAnInvalidCurrency() {
        mapped();

        assertThatThrownBy(() -> useCase.update(USER, WalletMode.PREPAID, "dollars", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAnUnmappedCustomer() {
        customers.save(Customer.pending(ids, USER, "Ada", "ada@example.com", NOW));

        assertThatThrownBy(() -> useCase.update(USER, WalletMode.PREPAID, "USD", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void defaultWalletIsOpenedOnlyOnce() {
        mapped();

        useCase.ensureDefaultWallet(USER);
        useCase.ensureDefaultWallet(USER);

        assertThat(wallets.opened).hasSize(1);
    }

    @Test
    void aWalletBillingRefusesLeavesTheAccountWaitingInsteadOfFailingRegistration() {
        mapped();
        wallets.unavailable();

        useCase.ensureDefaultWallet(USER);

        assertThat(wallets.opened).isEmpty();
        assertThat(settings.byCustomer).isEmpty();
    }

    @Test
    void theWalletIsOpenedOutsideTheTransactionThatSavesTheSetting() {
        mapped();

        useCase.update(USER, WalletMode.PREPAID, "USD", null);

        assertThat(wallets.openedWhileTransactionOpen)
                .as("a slow Billing must not hold a connection or a row lock")
                .isZero();
    }

    @Test
    void aSaveThatFailsAfterBillingOpenedTheWalletWritesNoSetting() {
        mapped();
        settings.fail = true;

        assertThatThrownBy(() -> useCase.update(USER, WalletMode.PREPAID, "USD", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(wallets.opened).as("Billing keeps the wallet the idempotency key names").hasSize(1);
        assertThat(settings.byCustomer).isEmpty();
    }

    private void mapped() {
        customers.save(Customer.pending(ids, USER, "Ada", "ada@example.com", NOW).mapped("cus_1"));
    }

    private static final class RecordingUnitOfWork implements UnitOfWork {
        private int transactions;
        private boolean open;

        boolean open() {
            return open;
        }

        @Override
        public <T> T transactional(Supplier<T> work) {
            transactions++;
            open = true;
            try {
                return work.get();
            } finally {
                open = false;
            }
        }
    }

    private final class InMemorySettings implements WalletSettingRepository {
        private final Map<UUID, CustomerWalletSetting> byCustomer = new LinkedHashMap<>();
        private boolean fail;

        @Override
        public Optional<CustomerWalletSetting> findByCustomerId(UUID customerId) {
            return Optional.ofNullable(byCustomer.get(customerId));
        }

        @Override
        public void save(CustomerWalletSetting setting) {
            if (fail) {
                throw new IllegalStateException("the setting row could not be written");
            }
            byCustomer.put(setting.customerId(), setting);
        }
    }

    private final class FakeWallets implements BillingWallets {
        private final List<OpenWallet> opened = new java.util.ArrayList<>();
        private final RecordingUnitOfWork unitOfWork;
        private OpenWallet last;
        private boolean unavailable;
        private int openedWhileTransactionOpen;

        private FakeWallets(RecordingUnitOfWork unitOfWork) {
            this.unitOfWork = unitOfWork;
        }

        void unavailable() {
            unavailable = true;
        }

        @Override
        public List<Wallet> wallets(String customerId) {
            return List.of(walletOf(customerId));
        }

        @Override
        public Wallet wallet(String id) {
            return walletOf("cus_1");
        }

        @Override
        public Wallet open(String idempotencyKey, OpenWallet request) {
            if (unavailable) {
                throw new UpstreamUnavailableException("billing is down");
            }
            if (unitOfWork.open()) {
                openedWhileTransactionOpen++;
            }
            opened.add(request);
            last = request;
            return walletOf(request.customerId());
        }

        private static Wallet walletOf(String customerId) {
            return new Wallet(
                    "wal_1", customerId, "USD", "PREPAID", new Amount(0, "USD"), new Amount(0, "USD"),
                    new Amount(0, "USD"), new Amount(0, "USD"), new Amount(0, "USD"), 0L, NOW);
        }
    }
}
