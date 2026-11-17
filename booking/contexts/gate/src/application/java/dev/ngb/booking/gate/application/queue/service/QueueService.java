package dev.ngb.booking.gate.application.queue.service;

import dev.ngb.booking.gate.application.exception.GateException;
import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.booking.gate.application.queue.port.WatermarkCache;
import dev.ngb.booking.gate.domain.queue.TicketClaims;
import dev.ngb.booking.gate.domain.queue.TicketTokens;
import dev.ngb.booking.gate.domain.token.CustomerTokens;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;

/**
 * The waiting room's decisions: issue an insert-only ticket, answer a poll from the cached watermark, and let one
 * admitted ticket fund exactly one hold.
 *
 * <p>The token carries the target and the ticket number, so a poll never reads a ticket row. The watermark may be up to
 * a second stale; the answer is therefore an admission bound, not an exact rank.
 */
public class QueueService {

    public record Ticket(String token, int retryAfterSeconds) {}

    public record Status(String status, Integer position, Integer retryAfterSeconds) {}

    public static final String WAITING = "WAITING";
    public static final String ADMITTED = "ADMITTED";
    public static final String USED = "USED";
    public static final String EXPIRED = "EXPIRED";
    public static final String LEFT = "LEFT";

    private final UnitOfWork unitOfWork;
    private final QueueTicketRepository tickets;
    private final WatermarkCache watermarks;
    private final CustomerTokens.Keys keys;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public QueueService(
            UnitOfWork unitOfWork,
            QueueTicketRepository tickets,
            WatermarkCache watermarks,
            CustomerTokens.Keys keys,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tickets = tickets;
        this.watermarks = watermarks;
        this.keys = keys;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Ticket enter(
            UUID offeringId, UUID sessionId, String customerRef, int admitPerSecond, int admitWindowSeconds) {
        String target = targetOf(offeringId, sessionId);
        Instant now = clock.now();
        long number = unitOfWork.transactional(() -> {
            tickets.ensureState(target, admitPerSecond, admitWindowSeconds, now);
            long next = tickets.nextTicketNo();
            if (!tickets.insert(target, next, customerRef, now)) {
                throw new GateException(409, "ALREADY_QUEUED", "This customer already has an active ticket.");
            }
            return next;
        });
        String token = TicketTokens.sign(
                new TicketClaims(
                        tenantContext.requireTenant(),
                        target,
                        offeringId,
                        number,
                        customerRef,
                        now.plusSeconds(3600),
                        keys.activeKeyId()),
                keys);
        return new Ticket(token, 1);
    }

    public Status status(TicketClaims claims, int admitPerSecond) {
        long watermark = unitOfWork.transactional(() -> watermarks.of(claims.targetId()));
        long position = Math.max(0, claims.ticketNo() - watermark);
        if (position == 0) {
            return new Status(ADMITTED, 0, null);
        }
        int retryAfter = (int) Math.max(1, Math.min(30, Math.ceil(position / (double) admitPerSecond)));
        return new Status(WAITING, (int) position, retryAfter);
    }

    /** Verifies the token and that it names this tenant, target and offering. */
    public TicketClaims verify(String token, UUID offeringId, UUID sessionId, String customerRef) {
        TicketClaims claims = TicketTokens.verify(token, keys).orElse(null);
        if (claims == null
                || !claims.tenantId().equals(tenantContext.requireTenant())
                || !claims.targetId().equals(targetOf(offeringId, sessionId))
                || !claims.customerRef().equals(customerRef)) {
            throw new GateException(403, "not-admitted", "The ticket does not admit this hold.");
        }
        if (claims.expired(clock.now())) {
            throw new GateException(403, "admission-expired", "The admission has expired.");
        }
        return claims;
    }

    /** Verifies a ticket token for a poll or a leave, where the token itself names the target. */
    public TicketClaims raw(String token) {
        TicketClaims claims = TicketTokens.verify(token, keys).orElse(null);
        if (claims == null || !claims.tenantId().equals(tenantContext.requireTenant())) {
            throw new GateException(403, "not-admitted", "The ticket does not admit this request.");
        }
        if (claims.expired(clock.now())) {
            throw new GateException(403, "admission-expired", "The admission has expired.");
        }
        return claims;
    }

    /** Claims the ticket for the hold about to be created; false when another request already used it. */
    public boolean use(TicketClaims claims, int admitWindowSeconds) {
        Instant cutoff = clock.now().minusSeconds(admitWindowSeconds);
        return unitOfWork.transactional(
                () -> tickets.markUsedIfAdmitted(claims.targetId(), claims.ticketNo(), cutoff));
    }

    public void release(TicketClaims claims) {
        unitOfWork.transactional(() -> {
            tickets.revertUsed(claims.targetId(), claims.ticketNo());
            return null;
        });
    }

    public void leave(TicketClaims claims) {
        unitOfWork.transactional(() -> {
            tickets.markLeft(claims.targetId(), claims.ticketNo());
            return null;
        });
    }

    public static String targetOf(UUID offeringId, UUID sessionId) {
        return sessionId != null ? "session:" + sessionId : "offering:" + offeringId;
    }
}
