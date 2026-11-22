package dev.ngb.billing.coupon.domain.event;

import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the coupon module announces. Each is written to the outbox in the same transaction as the change it reports,
 * so a Tenant learns the life of a redemption without polling.
 */
public final class CouponEvents {

    private CouponEvents() {}

    /** A coupon was redeemed onto a wallet. A credit coupon has already credited it; the rest apply to later charges. */
    public record CouponRedeemed(
            UUID eventId, Redemption redemption, String couponName, Benefit benefit, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.COUPON_REDEEMED;

        @Override
        public TenantId tenantId() {
            return redemption.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.wallet(redemption.walletId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload(
                    "redemptionId", redemption.id().toString(),
                    "couponId", redemption.couponId().toString(),
                    "codeId", redemption.codeId() == null ? null : redemption.codeId().toString(),
                    "walletId", redemption.walletId().toString(),
                    "customerId", redemption.customerId().toString(),
                    "benefit", benefitPayload(benefit),
                    "status", redemption.status().name(),
                    "maxApplications", redemption.maxApplications(),
                    "maxTotalDiscount", redemption.maxTotalDiscount() == null ? null : MoneyEventPayload.money(redemption.maxTotalDiscount()),
                    "endsAt", redemption.endsAt() == null ? null : redemption.endsAt().toString(),
                    "actor", redemption.actor());
        }
    }

    /** A redemption stopped discounting. */
    public record CouponRedemptionEnded(UUID eventId, Redemption redemption, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.COUPON_REDEMPTION_ENDED;

        @Override
        public TenantId tenantId() {
            return redemption.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.wallet(redemption.walletId());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload(
                    "redemptionId", redemption.id().toString(),
                    "couponId", redemption.couponId().toString(),
                    "walletId", redemption.walletId().toString(),
                    "customerId", redemption.customerId().toString(),
                    "status", redemption.status().name(),
                    "reason", redemption.endReason().name(),
                    "applicationsUsed", redemption.applicationsUsed(),
                    "discountTotal", MoneyEventPayload.money(redemption.discountTotal()),
                    "endedAt", redemption.endedAt().toString());
        }
    }

    private static Map<String, Object> benefitPayload(Benefit benefit) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (benefit) {
            case Benefit.PercentOff percent -> {
                map.put("type", percent.type());
                map.put("basisPoints", percent.basisPoints());
            }
            case Benefit.AmountOff amountOff -> {
                map.put("type", amountOff.type());
                map.put("amount", MoneyEventPayload.money(amountOff.amount()));
            }
            case Benefit.WalletCredit credit -> {
                map.put("type", credit.type());
                map.put("amount", MoneyEventPayload.money(credit.amount()));
            }
        }
        return map;
    }
}
