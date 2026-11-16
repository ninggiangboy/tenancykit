package dev.ngb.loyalty.points.application.earn.service;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The facts the points context announces, written to the outbox inside the transaction of the change that produced them.
 *
 * <p>Each payload is the {@code data} member of the event's schema, so a receiver reads the same fields the contract
 * describes. Nothing is announced for a replayed call or for a change of zero points, because nothing happened.
 */
public class PointsEvents {

    public static final String MEMBER_ENROLLED = "member.enrolled";
    public static final String POINTS_EARNED = "points.earned";
    public static final String POINTS_CLAWED_BACK = "points.clawed-back";
    public static final String POINTS_REDEEMED = "points.redeemed";
    public static final String POINTS_REDEMPTION_REVERSED = "points.redemption-reversed";

    private final EventPublisher publisher;
    private final IdGenerator ids;

    public PointsEvents(EventPublisher publisher, IdGenerator ids) {
        this.publisher = publisher;
        this.ids = ids;
    }

    /** A member was enrolled, either by an explicit call or by the first order. */
    public void memberEnrolled(
            TenantId tenantId, UUID memberId, String externalRef, Instant enrolledAt, boolean byOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("enrolledAt", enrolledAt.toString());
        payload.put("via", byOrder ? "ORDER" : "API");
        publish(tenantId, MEMBER_ENROLLED, memberId, enrolledAt, payload);
    }

    /** An order granted points, of which {@code debtRepaid} closed a debt instead of becoming a lot. */
    public void pointsEarned(
            TenantId tenantId,
            Instant at,
            UUID memberId,
            String externalRef,
            String orderRef,
            long points,
            long debtRepaid,
            long available,
            long debt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("orderRef", orderRef);
        payload.put("points", points);
        payload.put("debtRepaid", debtRepaid);
        payload.put("available", available);
        payload.put("debt", debt);
        publish(tenantId, POINTS_EARNED, memberId, at, payload);
    }

    /** A return took points back, of which {@code debtAdded} exceeded the lots it could consume. */
    public void pointsClawedBack(
            TenantId tenantId,
            Instant at,
            UUID memberId,
            String externalRef,
            String orderRef,
            String returnRef,
            long points,
            long debtAdded,
            long available,
            long debt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("orderRef", orderRef);
        payload.put("returnRef", returnRef);
        payload.put("points", points);
        payload.put("debtAdded", debtAdded);
        payload.put("available", available);
        payload.put("debt", debt);
        publish(tenantId, POINTS_CLAWED_BACK, memberId, at, payload);
    }

    /** A member spent points. The redemption names itself so the caller can reverse it later. */
    public void pointsRedeemed(
            TenantId tenantId,
            Instant at,
            UUID memberId,
            String externalRef,
            UUID redemptionId,
            long points,
            String reference,
            long available,
            long debt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("redemptionId", redemptionId.toString());
        payload.put("points", points);
        payload.put("reference", reference == null ? "" : reference);
        payload.put("available", available);
        payload.put("debt", debt);
        publish(tenantId, POINTS_REDEEMED, memberId, at, payload);
    }

    /** A redemption was reversed and its points went back to the lots it consumed. */
    public void pointsRedemptionReversed(
            TenantId tenantId,
            Instant at,
            UUID memberId,
            String externalRef,
            UUID redemptionId,
            long points,
            String reference,
            long available,
            long debt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("redemptionId", redemptionId.toString());
        payload.put("points", points);
        payload.put("reference", reference == null ? "" : reference);
        payload.put("available", available);
        payload.put("debt", debt);
        publish(tenantId, POINTS_REDEMPTION_REVERSED, memberId, at, payload);
    }

    private void publish(
            TenantId tenantId, String type, UUID memberId, Instant at, Map<String, Object> payload) {
        publisher.publish(new EventEnvelope(
                ids.newId(), tenantId, type, "member/" + memberId, at, payload));
    }
}
