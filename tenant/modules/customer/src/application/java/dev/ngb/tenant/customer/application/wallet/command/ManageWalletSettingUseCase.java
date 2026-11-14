package dev.ngb.tenant.customer.application.wallet.command;

import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.billing.api.model.Wallet;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.customer.application.wallet.port.WalletSettingRepository;
import dev.ngb.tenant.customer.domain.wallet.CustomerWalletSetting;
import dev.ngb.tenant.customer.domain.wallet.WalletMode;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.UpstreamUnavailableException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Scenario 13: the customer's setting selects the wallet mode on Billing's side. Opening the same
 * customer and currency again returns Billing's existing wallet, so an explicit update is safe.
 *
 * <p>The currency a customer starts in comes from configuration, and a wallet Billing refuses to open
 * at registration is logged against the account: the customer stays PENDING, and an explicit
 * wallet-settings update opens the wallet later.
 *
 * <p>The Billing call runs outside the transaction that writes the setting, so a slow Billing never holds a
 * connection or a row lock. A save that fails after Billing opened the wallet is recovered by calling again, which
 * uses the same idempotency key.
 */
public class ManageWalletSettingUseCase {

    private static final System.Logger LOG = System.getLogger(ManageWalletSettingUseCase.class.getName());

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final CustomerDirectory customers;
    private final WalletSettingRepository settings;
    private final BillingWallets wallets;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final String defaultCurrency;

    public ManageWalletSettingUseCase(
            CustomerDirectory customers,
            WalletSettingRepository settings,
            BillingWallets wallets,
            UnitOfWork unitOfWork,
            Clock clock,
            String defaultCurrency) {
        this.customers = customers;
        this.settings = settings;
        this.wallets = wallets;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.defaultCurrency = defaultCurrency;
    }

    public CustomerWalletSetting current(UUID userId) {
        CustomerDirectory.CustomerSnapshot customer = mapped(userId);
        return settings.findByCustomerId(customer.id())
                .orElseGet(() -> CustomerWalletSetting.defaults(customer.id(), defaultCurrency));
    }

    public CustomerWalletSetting update(UUID userId, WalletMode mode, String currency, Long creditLimit) {
        CustomerDirectory.CustomerSnapshot customer = mapped(userId);
        String normalized = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(normalized).matches()) {
            throw new BadRequestException("currency must be an ISO 4217 code");
        }
        if (mode == null) {
            throw new BadRequestException("a wallet mode is required");
        }
        if (creditLimit != null && creditLimit < 0) {
            throw new BadRequestException("a credit limit cannot be negative");
        }
        Long limit = mode == WalletMode.POSTPAID ? creditLimit : null;
        // The Billing call stays outside the transaction. Opening the wallet is safe to repeat: the idempotency key is
        // deterministic per (customer, currency), so a retry after a failed save returns the same wallet, never a second.
        Wallet wallet = wallets.open(
                IdempotencyKeys.wallet(customer.billingCustomerId(), normalized),
                new BillingWallets.OpenWallet(customer.billingCustomerId(), normalized, mode.name(), limit));
        CustomerWalletSetting setting = new CustomerWalletSetting(
                customer.id(), mode, normalized, limit, wallet.id(), clock.now());
        return unitOfWork.transactional(() -> {
            settings.save(setting);
            return setting;
        });
    }

    public void ensureDefaultWallet(UUID userId) {
        CustomerDirectory.CustomerSnapshot customer = customers.byUserId(userId);
        if (!customer.mapped() || settings.findByCustomerId(customer.id()).isPresent()) {
            return;
        }
        try {
            update(userId, WalletMode.PREPAID, defaultCurrency, null);
        } catch (UpstreamUnavailableException | ConflictException | BadRequestException e) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "user " + userId + ": the default " + defaultCurrency
                            + " wallet was not opened (" + e.getMessage()
                            + "); the account waits for an explicit wallet-settings update");
        }
    }

    private CustomerDirectory.CustomerSnapshot mapped(UUID userId) {
        CustomerDirectory.CustomerSnapshot customer = customers.byUserId(userId);
        if (!customer.mapped()) {
            throw new BadRequestException("this account has no Billing customer mapping yet");
        }
        return customer;
    }
}
