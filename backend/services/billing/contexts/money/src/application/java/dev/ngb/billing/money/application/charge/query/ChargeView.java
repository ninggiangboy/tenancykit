package dev.ngb.billing.money.application.charge.query;

import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;

import java.time.Instant;
import java.util.List;

/** A charge as the read API shows it. {@code grossAmount} is what it was worth before coupons. */
public record ChargeView(
        ChargeId id,
        WalletId walletId,
        ChargeStatus status,
        String failureReason,
        Money amount,
        Money grossAmount,
        List<DiscountLine> discounts,
        ReservationId reservationId,
        String usageRef,
        String reference,
        ChargeSource source,
        Instant createdAt) {}
