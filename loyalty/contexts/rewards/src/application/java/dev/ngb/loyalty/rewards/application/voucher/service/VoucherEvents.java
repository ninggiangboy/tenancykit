package dev.ngb.loyalty.rewards.application.voucher.service;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The facts the rewards context announces, written to the outbox inside the transaction of the change that produced them.
 *
 * <p>Each payload is the {@code data} member of the event's schema, so a receiver reads the same fields the contract
 * describes. A voucher's id is the key a Tenant should derive its own idempotency keys from; the code is only a token,
 * which is why every payload carries both. Nothing is announced for a replayed use, because nothing happened.
 */
public class VoucherEvents {

    public static final String VOUCHER_ISSUED = "voucher.issued";
    public static final String VOUCHER_USED = "voucher.used";
    public static final String VOUCHER_VOIDED = "voucher.voided";

    private final EventPublisher publisher;
    private final IdGenerator ids;

    public VoucherEvents(EventPublisher publisher, IdGenerator ids) {
        this.publisher = publisher;
        this.ids = ids;
    }

    /** A reward redemption issued a single-use voucher. */
    public void voucherIssued(TenantId tenantId, Instant at, Voucher voucher) {
        Map<String, Object> payload = identity(voucher);
        payload.put("expiresAt", voucher.expiresAt().toString());
        publish(tenantId, VOUCHER_ISSUED, voucher, at, payload);
    }

    /** A voucher was consumed, by the caller's own reference for the use. */
    public void voucherUsed(TenantId tenantId, Instant at, Voucher voucher) {
        Map<String, Object> payload = identity(voucher);
        payload.put("expiresAt", voucher.expiresAt().toString());
        payload.put("useRef", voucher.useRef());
        payload.put("usedAt", voucher.usedAt().toString());
        publish(tenantId, VOUCHER_USED, voucher, at, payload);
    }

    /**
     * A voucher stopped being usable without being used. An {@code ERASURE} void released the member's reference, so the
     * payload carries a null one.
     */
    public void voucherVoided(TenantId tenantId, Instant at, Voucher voucher, VoidReason reason) {
        Map<String, Object> payload = identity(voucher);
        payload.put("expiresAt", voucher.expiresAt().toString());
        payload.put("reason", reason.name());
        if (reason == VoidReason.ERASURE) {
            payload.put("externalRef", null);
        }
        publish(tenantId, VOUCHER_VOIDED, voucher, at, payload);
    }

    /** The voucher fields all three events share, in the order the contract lists them. */
    private static Map<String, Object> identity(Voucher voucher) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("voucherId", voucher.id().value().toString());
        payload.put("code", voucher.code());
        payload.put("rewardId", voucher.rewardId().toString());
        payload.put("redemptionId", voucher.redemptionId().toString());
        payload.put("memberId", voucher.memberId().toString());
        payload.put("externalRef", voucher.externalRef());
        return payload;
    }

    private void publish(TenantId tenantId, String type, Voucher voucher, Instant at, Map<String, Object> payload) {
        publisher.publish(new EventEnvelope(
                ids.newId(), tenantId, type, "member/" + voucher.memberId(), at, payload));
    }
}
