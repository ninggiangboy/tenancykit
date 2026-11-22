package dev.ngb.billing.money.adapter.reservation.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.api.reservation.ReservationResult;
import dev.ngb.billing.money.api.reservation.ReserveBudgetCommand;
import dev.ngb.billing.money.application.reservation.command.ReleaseReservationUseCase;
import dev.ngb.billing.money.application.reservation.command.ReserveBudgetUseCase;
import dev.ngb.billing.money.application.reservation.query.ReservationListing;
import dev.ngb.billing.money.application.reservation.query.ReservationView;
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
import java.time.Duration;
import java.time.Instant;
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

/** Holds part of a wallet's balance for a later charge. Every money-moving POST needs an {@code Idempotency-Key}. */
@Tag(
        name = "Reservations",
        description =
                "Hold part of a wallet's balance for a later charge.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ReservationController {

    public record ReserveRequest(String walletId, long amount, String currency, Long ttlSeconds, String reference) {}

    public record ReservationResponse(
            String id, String walletId, String status, MoneyDto amount, MoneyDto captured, Instant expiresAt, String reference, Instant createdAt,
            Instant closedAt) {
        static ReservationResponse ofView(ReservationView r) {
            return new ReservationResponse(r.id().toString(), r.walletId().toString(), r.status().name(), MoneyDto.of(r.amount()),
                    MoneyDto.of(r.captured()), r.expiresAt(), r.reference(), r.createdAt(), r.closedAt());
        }
    }

    private final ReserveBudgetUseCase reserve;
    private final ReleaseReservationUseCase release;
    private final WalletQueries queries;
    private final CurrentActor currentActor;

    public ReservationController(
            ReserveBudgetUseCase reserve, ReleaseReservationUseCase release, WalletQueries queries, CurrentActor currentActor) {
        this.reserve = reserve;
        this.release = release;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Reserve part of a wallet's balance",
            description =
                    "Holds `amount` of the wallet for a bounded time (`ttlSeconds`) for a later charge, after checking "
                    + "the wallet can cover it or its postpaid limit. Nothing is posted to the ledger until the reservation "
                    + "is captured. Send an `Idempotency-Key`; a repeat of the same key and body answers the first result "
                    + "with `200`.")
    @PostMapping("/reservations")
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.RESERVATION_MANAGE)
    public ResponseEntity<ReservationResponse> reserve(@RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String key, @RequestBody ReserveRequest request) {
        ReservationResult result = reserve.reserve(new ReserveBudgetCommand(
                WalletId.of(request.walletId()), new MoneyDto(request.amount(), request.currency()).toMoney(),
                Duration.ofSeconds(request.ttlSeconds() == null ? 900 : request.ttlSeconds()), request.reference(), key, currentActor.caller()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ReservationResponse.ofView(queries.reservation(result.id(), currentActor.caller())));
    }

    /** Filter, sort and paging follow the list-query contract; a Tenant System sees only its customers' wallets. */
    @Operation(
            summary = "List reservations",
            description =
                    "Pages through the tenant's reservations, with the list-query contract's filtering, sorting and "
                    + "paging. A Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping("/reservations")
    @Listing(ReservationListing.class)
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public Page<ReservationResponse> reservations(ListQuery query) {
        return queries.reservations(query, currentActor.caller()).map(ReservationResponse::ofView);
    }

    @Operation(
            summary = "Get a reservation",
            description =
                    "Returns one reservation with its status, amount, what was captured and when it expires. An unknown "
                    + "id answers `404`.")
    @GetMapping("/reservations/{id}")
    @RequiresPermission(BillingPermissions.WALLET_READ)
    public ReservationResponse reservation(@PathVariable String id) {
        return ReservationResponse.ofView(queries.reservation(ReservationId.of(id), currentActor.caller()));
    }

    @Operation(
            summary = "Release a reservation",
            description =
                    "Gives the held amount back to the wallet. Releasing a reservation that is already released changes "
                    + "nothing.")
    @PostMapping("/reservations/{id}/release")
    @RequiresPermission(BillingPermissions.RESERVATION_MANAGE)
    public ReservationResponse release(@PathVariable String id) {
        release.execute(ReservationId.of(id), currentActor.caller());
        return ReservationResponse.ofView(queries.reservation(ReservationId.of(id), currentActor.caller()));
    }
}
