package dev.ngb.loyalty.points.application.earn.service;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.port.LiveLot;
import dev.ngb.loyalty.points.application.ledger.port.PointLotRow;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns the difference between what an order is worth and what it was already granted into ledger entries.
 *
 * <p>A positive difference repays the member's debt first and turns the rest into a lot that expires
 * {@code expiryMonths} after it was earned. A negative difference is a clawback: it consumes the order's own lot first,
 * then the lots that expire soonest, and whatever it cannot take becomes debt. A difference of zero writes nothing, which
 * is what makes a replayed call change nothing at all.
 */
public class PointsApplier {

    /** What one application did, for the caller to report and to announce. */
    public record Applied(
            long debtRepaid, long debtAdded, long debtAfter, long available, long lotGranted, UUID entryId) {}

    private final LedgerRepository ledger;
    private final IdGenerator ids;

    public PointsApplier(LedgerRepository ledger, IdGenerator ids) {
        this.ledger = ledger;
        this.ids = ids;
    }

    /**
     * Grants points: what the member owes is repaid first and the rest becomes a lot.
     *
     * @param expiryMonths how long the lot lives, which is the program's current rule
     */
    public Applied grant(MemberRow member, long points, UUID orderId, Instant now, int expiryMonths) {
        long debtRepaid = Math.min(member.debt(), points);
        long debtAfter = member.debt() - debtRepaid;
        long toLot = points - debtRepaid;
        UUID entryId = ids.newId();
        ledger.addEntry(new LedgerEntryRow(entryId, member.id(), LedgerKind.EARN, points, orderId, null, now));
        if (toLot > 0) {
            ledger.addLot(new PointLotRow(
                    ids.newId(), member.id(), entryId, orderId, toLot, now, expiry(now, expiryMonths)));
        }
        return new Applied(debtRepaid, 0L, debtAfter, balance(member, debtAfter, now), toLot, entryId);
    }

    /** Nothing moved: the member keeps exactly what it had, and no entry is written. */
    public Applied quiet(MemberRow member, Instant now) {
        return new Applied(0L, 0L, member.debt(), balance(member, member.debt(), now), 0L, null);
    }

    /** Takes points back for a return: the order's own lot first, then the lots that expire soonest, then debt. */
    public Applied takeBack(MemberRow member, long points, UUID orderId, UUID returnId, Instant now) {
        UUID entryId = ids.newId();
        ledger.addEntry(new LedgerEntryRow(entryId, member.id(), LedgerKind.CLAWBACK, points, orderId, returnId, now));
        long outstanding = points;
        for (LiveLot lot : consumable(ledger.liveLots(member.id(), now), orderId)) {
            if (outstanding == 0) {
                break;
            }
            long taken = Math.min(lot.remaining(), outstanding);
            ledger.consume(lot.lotId(), taken);
            ledger.addAllocation(entryId, lot.lotId(), taken);
            outstanding -= taken;
        }
        long debtAfter = member.debt() + outstanding;
        return new Applied(0L, outstanding, debtAfter, balance(member, debtAfter, now), 0L, entryId);
    }

    /** The order's own lot first, then the lots that expire soonest: what a clawback consumes, in that order. */
    private static List<LiveLot> consumable(List<LiveLot> lots, UUID orderId) {
        return lots.stream()
                .sorted(Comparator.comparing((LiveLot lot) -> !Objects.equals(lot.orderId(), orderId))
                        .thenComparing(LiveLot::expiresAt)
                        .thenComparing(LiveLot::lotId))
                .toList();
    }

    private long balance(MemberRow member, long debt, Instant now) {
        return ledger.liveBalance(member.id(), now) - debt;
    }

    /** Processing time, not the order's own instant: a backdated order grants a lot that expires from today. */
    private static Instant expiry(Instant earnedAt, int expiryMonths) {
        return earnedAt.atZone(ZoneOffset.UTC).plusMonths(expiryMonths).toInstant();
    }
}
