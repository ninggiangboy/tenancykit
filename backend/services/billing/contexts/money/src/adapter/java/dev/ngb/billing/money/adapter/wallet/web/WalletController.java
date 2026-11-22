package dev.ngb.billing.money.adapter.wallet.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.application.wallet.command.OpenWalletUseCase;
import dev.ngb.billing.money.application.wallet.query.WalletListing;
import dev.ngb.billing.money.application.wallet.query.WalletQueries;
import dev.ngb.billing.money.application.wallet.query.WalletView;
import dev.ngb.billing.money.domain.wallet.Wallet;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Opens and reads wallets. Amounts are integers in minor units (cents, dong) with an ISO 4217 currency code. */
@Tag(
        name = "Wallets",
        description =
                "Open and read wallets. Amounts are integers in minor units with an ISO 4217 currency.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class WalletController {

    public record OpenWalletRequest(String customerId, String currency, String mode, Long creditLimit) {}

    public record WalletResponse(
            String id, String customerId, String currency, String mode, MoneyDto balance, MoneyDto held, MoneyDto available, MoneyDto creditLimit,
            MoneyDto outstanding, long lastSequence, Instant createdAt) {

        static WalletResponse of(Wallet w) {
            return of(w.id().toString(), w.customerId().toString(), w.currency().code(), w.mode().name(), w.balance(), w.held(),
                    w.available(), w.creditLimit(), w.outstanding(), w.lastSequence(), w.createdAt());
        }

        static WalletResponse ofView(WalletView w) {
            return of(w.id().toString(), w.customerId().toString(), w.currency().code(), w.mode().name(), w.balance(), w.held(),
                    w.available(), w.creditLimit(), w.outstanding(), w.lastSequence(), w.createdAt());
        }

        private static WalletResponse of(
                String id, String customerId, String currency, String mode, Money balance, Money held, Money available, Money creditLimit,
                Money outstanding, long lastSequence, Instant createdAt) {
            return new WalletResponse(id, customerId, currency, mode, MoneyDto.of(balance), MoneyDto.of(held), MoneyDto.of(available),
                    MoneyDto.of(creditLimit), MoneyDto.of(outstanding), lastSequence, createdAt);
        }
    }

    private final OpenWalletUseCase openWallet;
    private final WalletQueries queries;
    private final CurrentActor currentActor;

    public WalletController(OpenWalletUseCase openWallet, WalletQueries queries, CurrentActor currentActor) {
        this.openWallet = openWallet;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Open a wallet",
            description =
                    "Opens a customer's wallet in one `currency`, as prepaid or postpaid (`mode`, with a `creditLimit` "
                    + "for postpaid). Amounts are integers in minor units. Asking again with the same settings answers the "
                    + "existing wallet; different settings are a conflict, because a wallet's currency and mode never "
                    + "change.")
    @PostMapping("/wallets")
    @RequiresPermission(BillingPermissions.WALLET_MANAGE)
    public ResponseEntity<WalletResponse> openWallet(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody OpenWalletRequest request) {
        Currency currency = Currency.of(request.currency());
        WalletMode mode = WalletMode.valueOf(request.mode());
        Money limit = request.creditLimit() == null ? null : Money.of(request.creditLimit(), currency);
        OpenWalletUseCase.Result result = openWallet.execute(new OpenWalletUseCase.Command(
                CustomerId.of(request.customerId()), currency, mode, limit, Audit.of(currentActor.get().auditName(), requestId), currentActor.caller()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(WalletResponse.of(result.wallet()));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link WalletListing}. */
    @Operation(
            summary = "List wallets",
            description =
                    "Pages through the tenant's wallets, with the list-query contract's filtering, sorting and paging. A "
                    + "Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/wallets")
    @Listing(WalletListing.class)
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public Page<WalletResponse> wallets(ListQuery query) {
        return queries.wallets(query, currentActor.caller()).map(WalletResponse::ofView);
    }

    @Operation(
            summary = "Get a wallet",
            description =
                    "Returns one wallet with its balance, the amount held by reservations and the amount available to "
                    + "spend. An unknown id answers `404`.")
    @GetMapping("/wallets/{id}")
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public WalletResponse wallet(@PathVariable String id) {
        return WalletResponse.ofView(queries.wallet(WalletId.of(id), currentActor.caller()));
    }
}
