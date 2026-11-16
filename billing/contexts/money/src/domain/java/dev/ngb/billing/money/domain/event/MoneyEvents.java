package dev.ngb.billing.money.domain.event;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.billing.money.api.event.MoneyEventTypes;
import dev.ngb.billing.money.domain.charge.Charge;
import dev.ngb.billing.money.domain.ledger.LedgerEntry;
import dev.ngb.billing.money.domain.reservation.Reservation;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the money path announces. Each event is written to the outbox in the same transaction as the
 * change it reports. Payloads are the integration contract: ids, amounts in minor units and currency codes,
 * never personal data.
 */
public final class MoneyEvents {

    private MoneyEvents() {}

    /** Money was added to a wallet. */
    public record WalletCredited(UUID eventId, LedgerEntry entry, Money balanceAfter, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.WALLET_CREDITED;

        @Override
        public TenantId tenantId() {
            return entry.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.wallet(entry.walletId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("walletId", entry.walletId().toString(), "entryId", entry.id().toString(), "sequence", entry.sequence(),
                    "amount", MoneyEventPayload.money(entry.amount()), "balanceAfter", MoneyEventPayload.money(balanceAfter), "reference", entry.source().reference());
        }
    }

    /** Money was taken from a wallet outside a charge. */
    public record WalletDebited(UUID eventId, LedgerEntry entry, Money balanceAfter, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.WALLET_DEBITED;

        @Override
        public TenantId tenantId() {
            return entry.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.wallet(entry.walletId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("walletId", entry.walletId().toString(), "entryId", entry.id().toString(), "sequence", entry.sequence(),
                    "amount", MoneyEventPayload.money(entry.amount().negate()), "balanceAfter", MoneyEventPayload.money(balanceAfter), "reference", entry.source().reference());
        }
    }

    public record ReservationCreated(UUID eventId, Reservation reservation, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.RESERVATION_CREATED;

        @Override
        public TenantId tenantId() {
            return reservation.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.reservation(reservation.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("reservationId", reservation.id().toString(), "walletId", reservation.walletId().toString(),
                    "amount", MoneyEventPayload.money(reservation.amount()), "expiresAt", reservation.expiresAt().toString(), "reference", reservation.reference());
        }
    }

    /** A reservation ended without a capture: released by the caller, or expired. */
    public record ReservationReleased(UUID eventId, Reservation reservation, boolean expired, Instant occurredAt) implements DomainEvent {
        public static final String RELEASED = SuiteEventTypes.RESERVATION_RELEASED;
        public static final String EXPIRED = SuiteEventTypes.RESERVATION_EXPIRED;

        @Override
        public TenantId tenantId() {
            return reservation.tenantId();
        }

        @Override
        public String type() {
            return expired ? EXPIRED : RELEASED;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.reservation(reservation.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("reservationId", reservation.id().toString(), "walletId", reservation.walletId().toString(),
                    "amount", MoneyEventPayload.money(reservation.amount()), "reference", reservation.reference());
        }
    }

    public record ChargeSucceeded(UUID eventId, Charge charge, LedgerEntry entry, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = MoneyEventTypes.CHARGE_SUCCEEDED;

        @Override
        public TenantId tenantId() {
            return charge.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.charge(charge.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("chargeId", charge.id().toString(), "walletId", charge.walletId().toString(),
                    "entryId", entry == null ? null : entry.id().toString(),
                    "amount", MoneyEventPayload.money(charge.amount()), "grossAmount", MoneyEventPayload.money(charge.grossAmount()),
                    "discounts", discounts(charge), "source", charge.source().name(), "usageRef", charge.usageRef(),
                    "reservationId", charge.reservationId() == null ? null : charge.reservationId().toString(), "reference", charge.reference());
        }
    }

    public record ChargeFailed(UUID eventId, Charge charge, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.CHARGE_FAILED;

        @Override
        public TenantId tenantId() {
            return charge.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.charge(charge.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("chargeId", charge.id().toString(), "walletId", charge.walletId().toString(),
                    "amount", MoneyEventPayload.money(charge.amount()), "grossAmount", MoneyEventPayload.money(charge.grossAmount()),
                    "source", charge.source().name(), "usageRef", charge.usageRef(), "reason", charge.failureReason(),
                    "reference", charge.reference());
        }
    }

    /** The discount lines of a charge, in the order they applied, or null when there were none. */
    private static java.util.List<Map<String, Object>> discounts(Charge charge) {
        if (charge.discounts().isEmpty()) {
            return null;
        }
        return charge.discounts().stream()
                .map(line -> MoneyEventPayload.payload("redemptionId", line.redemptionId().toString(), "couponId", line.couponId().toString(),
                        "couponName", line.couponName(), "benefitType", line.benefitType(), "amount", MoneyEventPayload.money(line.amount())))
                .toList();
    }

    /** An earlier entry was corrected. Invoicing reacts to this; the original entry is never edited. */
    public record EntryReversed(UUID eventId, LedgerEntry reversal, LedgerEntry original, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.LEDGER_ENTRY_REVERSED;

        @Override
        public TenantId tenantId() {
            return reversal.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.wallet(reversal.walletId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("walletId", reversal.walletId().toString(), "originalEntryId", original.id().toString(),
                    "reversalEntryId", reversal.id().toString(), "amount", MoneyEventPayload.money(reversal.amount()), "reason", reversal.origin().reason(),
                    "chargeId", original.source().chargeId() == null ? null : original.source().chargeId().toString());
        }
    }
}
