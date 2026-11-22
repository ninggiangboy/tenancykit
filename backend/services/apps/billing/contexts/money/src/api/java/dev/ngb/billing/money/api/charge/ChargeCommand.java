package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.Objects;
import java.util.UUID;
import dev.ngb.billing.money.api.reservation.ReservationId;

/**
 * Record the actual cost incurred. The source follows from the fields: a {@code reservationId} charges from
 * that reservation (releasing the unused remainder), a {@code usageRef} charges from usage, neither is a
 * standalone charge. For a reservation the wallet is the reservation's and {@code walletId} may be null.
 * {@code caller} is the Tenant System acting over HTTP, or null for a trusted in-process caller.
 *
 * <p>{@code amount} is the <em>gross</em>: what the charge would cost before any coupon. The discount is decided on
 * the server and never supplied by a client, which is why the context is filled by trusted callers only and the HTTP
 * layer sets just the kind. The context is deliberately not part of the replay fingerprint: the same charge replayed
 * returns the recorded result whatever context the retry names.
 */
public record ChargeCommand(
        WalletId walletId, ReservationId reservationId, Money amount, String usageRef, String reference, String idempotencyKey, UUID caller,
        ChargeContext context) {

    public ChargeCommand(
            WalletId walletId, ReservationId reservationId, Money amount, String usageRef, String reference, String idempotencyKey) {
        this(walletId, reservationId, amount, usageRef, reference, idempotencyKey, null, null);
    }

    public ChargeCommand(
            WalletId walletId, ReservationId reservationId, Money amount, String usageRef, String reference, String idempotencyKey,
            UUID caller) {
        this(walletId, reservationId, amount, usageRef, reference, idempotencyKey, caller, null);
    }

    public ChargeCommand {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (walletId == null && reservationId == null) {
            throw new IllegalArgumentException("a wallet or a reservation is required");
        }
        if (reservationId != null && usageRef != null) {
            throw new IllegalArgumentException("a charge is from a reservation or from usage, not both");
        }
    }

    public ChargeSource source() {
        return reservationId != null ? ChargeSource.RESERVATION : usageRef != null ? ChargeSource.USAGE : ChargeSource.STANDALONE;
    }

    /** The kind the discount engine sees: the caller's when it named one, otherwise the one the source implies. */
    public ChargeKind kind() {
        if (context != null) {
            return context.kind();
        }
        return switch (source()) {
            case USAGE -> ChargeKind.USAGE;
            case RESERVATION -> ChargeKind.RESERVATION;
            case STANDALONE -> ChargeKind.STANDALONE;
        };
    }

    /** The context to hand the discount seam, with the kind always filled in. */
    public ChargeContext discountContext() {
        return context != null ? context : ChargeContext.of(kind());
    }
}
