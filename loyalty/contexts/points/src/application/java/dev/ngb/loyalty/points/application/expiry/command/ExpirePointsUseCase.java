package dev.ngb.loyalty.points.application.expiry.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.expiry.port.DueLot;
import dev.ngb.loyalty.points.application.expiry.port.ExpiryRepository;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import dev.ngb.loyalty.points.domain.member.MemberStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes off the lots whose expiry has passed, one member at a time, so the ledger says what the balance already said.
 *
 * <p>What a member can spend never depends on this run: an expired lot is excluded from the balance and from every spend
 * the moment it expires. The run only records the fact, and it does so under the lock order every other flow uses — the
 * member's row first, then its lots in ascending id — so a spend that races it cannot take a point twice and a second
 * run finds nothing left to write off. A lot another flow emptied first is skipped, and the member's debt is never
 * touched: expiring points are not a return and never create an obligation.
 */
public class ExpirePointsUseCase {

    /** What one run over one tenant did: the members it reached, the lots it wrote off and the points they held. */
    public record Result(int members, int lots, long points) {

        public static final Result NOTHING = new Result(0, 0, 0L);
    }

    /** One member's expiry transaction, as the run counts it. */
    private record Expired(int lots, long points) {}

    private final UnitOfWork unitOfWork;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final ExpiryRepository expiry;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final int batchSize;

    public ExpirePointsUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            LedgerRepository ledger,
            ExpiryRepository expiry,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            int batchSize) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.ledger = ledger;
        this.expiry = expiry;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass over the tenant. The batch is bounded and nothing is locked while members are found, so a tenant coming
     * back from downtime is caught up over several passes instead of holding a transaction for the whole sweep.
     */
    public Result runOnce() {
        Instant now = clock.now();
        List<UUID> due = unitOfWork.transactional(() -> expiry.dueMemberIds(now, batchSize));
        int reached = 0;
        int lots = 0;
        long points = 0L;
        for (UUID memberId : due) {
            Optional<Expired> expired = unitOfWork.transactional(() -> expire(memberId, now));
            if (expired.isEmpty()) {
                continue;
            }
            reached++;
            lots += expired.get().lots();
            points += expired.get().points();
        }
        return new Result(reached, lots, points);
    }

    /** One member's short transaction: the member lock, then its due lots, then the entries that record the write-off. */
    private Optional<Expired> expire(UUID memberId, Instant now) {
        TenantId tenantId = tenantContext.requireTenant();
        Optional<MemberRow> locked = members.lockById(memberId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MemberRow member = locked.get();
        if (member.status() == MemberStatus.ERASED) {
            return Optional.empty();
        }
        List<DueLot> due = expiry.lockDueLots(memberId, now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        List<DueLot> writeOffs = new ArrayList<>();
        for (DueLot lot : inExpiryOrder(due)) {
            if (!expiry.writeOff(lot.lotId())) {
                // Another flow emptied the lot between the re-read and the write; there is nothing left to write off.
                continue;
            }
            UUID entryId = ids.newId();
            ledger.addEntry(new LedgerEntryRow(
                    entryId, memberId, LedgerKind.EXPIRE, lot.remaining(), null, null, null, now));
            ledger.addAllocation(entryId, lot.lotId(), lot.remaining());
            writeOffs.add(lot);
        }
        if (writeOffs.isEmpty()) {
            return Optional.empty();
        }
        long available = ledger.liveBalance(memberId, now) - member.debt();
        events.pointsExpired(tenantId, now, memberId, member.externalRef(), writeOffs, available, member.debt());
        return Optional.of(new Expired(
                writeOffs.size(), writeOffs.stream().mapToLong(DueLot::remaining).sum()));
    }

    /** Earliest expiry first, which is the order the lots were worth and the order the ledger records them in. */
    private static List<DueLot> inExpiryOrder(List<DueLot> lots) {
        return lots.stream()
                .filter(lot -> lot.remaining() > 0)
                .sorted(Comparator.comparing(DueLot::expiresAt).thenComparing(DueLot::lotId))
                .toList();
    }
}
