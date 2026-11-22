package dev.ngb.billing.money.adapter.charge.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeResult;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.application.charge.command.ChargeUseCase;
import dev.ngb.billing.money.application.charge.query.ChargeListing;
import dev.ngb.billing.money.application.charge.query.ChargeView;
import dev.ngb.billing.money.application.wallet.query.WalletQueries;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.money.MoneyDto;
import dev.ngb.billing.sharedkernel.id.WalletId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Charges a wallet directly or by capturing a reservation. Every money-moving POST needs an {@code Idempotency-Key}. */
@Tag(
        name = "Charges",
        description =
                "Charge a wallet directly or by capturing a reservation.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ChargeController {

    public record CaptureRequest(long amount, String currency, String reference) {}

    public record ChargeRequest(String walletId, long amount, String currency, String usageRef, String reference) {}

    public record DiscountLineResponse(String redemptionId, String couponId, String couponName, String benefitType, MoneyDto amount) {
        static DiscountLineResponse of(DiscountLine line) {
            return new DiscountLineResponse(line.redemptionId().toString(), line.couponId().toString(), line.couponName(), line.benefitType(),
                    MoneyDto.of(line.amount()));
        }
    }

    public record ChargeResponse(
            String id, String status, String failureReason, MoneyDto amount, MoneyDto grossAmount, java.util.List<DiscountLineResponse> discounts,
            String ledgerEntryId, String reservationId, String usageRef, String reference) {
        static ChargeResponse ofView(ChargeResult result, ChargeView charge) {
            return new ChargeResponse(result.id().toString(), result.status().name(), result.failureReason(), MoneyDto.of(result.amount()),
                    MoneyDto.of(charge.grossAmount()),
                    charge.discounts().stream().map(DiscountLineResponse::of).toList(),
                    result.ledgerEntryId() == null ? null : result.ledgerEntryId().toString(),
                    charge.reservationId() == null ? null : charge.reservationId().toString(), charge.usageRef(), charge.reference());
        }

        static ChargeResponse ofView(ChargeView charge) {
            return new ChargeResponse(charge.id().toString(), charge.status().name(), charge.failureReason(), MoneyDto.of(charge.amount()),
                    MoneyDto.of(charge.grossAmount()),
                    charge.discounts().stream().map(DiscountLineResponse::of).toList(),
                    null, charge.reservationId() == null ? null : charge.reservationId().toString(), charge.usageRef(), charge.reference());
        }
    }

    private final ChargeUseCase charge;
    private final WalletQueries queries;
    private final CurrentActor currentActor;

    public ChargeController(ChargeUseCase charge, WalletQueries queries, CurrentActor currentActor) {
        this.charge = charge;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Charges the actual cost against the reservation and releases whatever was not used. */
    @Operation(
            summary = "Capture a reservation",
            description =
                    "Charges the actual cost against the reservation and releases whatever was not used. Send an "
                    + "`Idempotency-Key`; a repeat of the same key and body answers the first result with `200`.")
    @PostMapping("/reservations/{id}/capture")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.CHARGE_CREATE)
    public ResponseEntity<ChargeResponse> capture(
            @PathVariable String id, @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestBody CaptureRequest request) {
        ChargeResult result = charge.charge(new ChargeCommand(
                null, ReservationId.of(id), new MoneyDto(request.amount(), request.currency()).toMoney(), null, request.reference(), key, currentActor.caller()));
        return chargeResponse(result, currentActor.caller());
    }

    @Operation(
            summary = "Charge a wallet",
            description =
                    "Records the actual cost incurred against a wallet (`walletId`, `amount` in minor units, `currency`), "
                    + "once per `Idempotency-Key`. A wallet that cannot pay yields a recorded `insufficient_funds` failure "
                    + "rather than an error. A repeat answers the first charge with `200`.")
    @PostMapping("/charges")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.CHARGE_CREATE)
    public ResponseEntity<ChargeResponse> charge(@RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestBody ChargeRequest request) {
        ChargeResult result = charge.charge(new ChargeCommand(
                WalletId.of(request.walletId()), null, new MoneyDto(request.amount(), request.currency()).toMoney(), request.usageRef(),
                request.reference(), key, currentActor.caller()));
        return chargeResponse(result, currentActor.caller());
    }

    /** Filter, sort and paging follow the list-query contract; a Tenant System sees only its customers' wallets. */
    @Operation(
            summary = "List charges",
            description =
                    "Pages through the tenant's charges, with the list-query contract's filtering, sorting and paging. A "
                    + "Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/charges")
    @Listing(ChargeListing.class)
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public Page<ChargeResponse> charges(ListQuery query) {
        return queries.charges(query, currentActor.caller()).map(ChargeResponse::ofView);
    }

    @Operation(
            summary = "Get a charge",
            description =
                    "Returns one charge with the discounts applied to it. An unknown id answers `404`.")
    @GetMapping("/charges/{id}")
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public ChargeResponse getCharge(@PathVariable String id) {
        ChargeView found = queries.charge(ChargeId.of(id), currentActor.caller());
        return ChargeResponse.ofView(
                new ChargeResult(found.id(), found.status(), found.failureReason(), found.amount(), null, false, found.grossAmount(),
                        found.discounts()), found);
    }

    /**
     * 201 for a new recorded charge, 200 when a replayed key returns the earlier charge, and 402 when the wallet
     * could not pay (the failure is recorded and repeatable).
     */
    private ResponseEntity<ChargeResponse> chargeResponse(ChargeResult result, UUID caller) {
        ChargeView stored = queries.charge(result.id(), caller);
        HttpStatus status = result.status() == ChargeStatus.FAILED
                ? HttpStatus.PAYMENT_REQUIRED
                : result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ChargeResponse.ofView(result, stored));
    }
}
