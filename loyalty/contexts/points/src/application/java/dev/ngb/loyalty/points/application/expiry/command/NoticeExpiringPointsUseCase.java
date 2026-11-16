package dev.ngb.loyalty.points.application.expiry.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.expiry.port.DueLot;
import dev.ngb.loyalty.points.application.expiry.port.ExpiryRepository;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.domain.member.MemberStatus;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.program.ProgramView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells the Tenant, ahead of time, which of a member's lots are about to expire.
 *
 * <p>The notice changes nothing a member holds: it only records that the lot was announced, so the same lot is announced
 * once however often the run repeats, and queues one event per member for everything that came into the program's notice
 * window since the last run. A lot already spent, already written off or already past its expiry is left alone, and a
 * program whose notice window is zero is never read at all.
 */
public class NoticeExpiringPointsUseCase {

    /** What one run over one tenant did: the members it announced, the lots it named and the points they still held. */
    public record Result(int members, int lots, long points) {

        public static final Result NOTHING = new Result(0, 0, 0L);
    }

    /** One member's notice transaction, as the run counts it. */
    private record Announced(int lots, long points) {}

    private final UnitOfWork unitOfWork;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final ExpiryRepository expiry;
    private final ProgramReader programReader;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final int batchSize;

    public NoticeExpiringPointsUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            LedgerRepository ledger,
            ExpiryRepository expiry,
            ProgramReader programReader,
            PointsEvents events,
            TenantContext tenantContext,
            Clock clock,
            int batchSize) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.ledger = ledger;
        this.expiry = expiry;
        this.programReader = programReader;
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass over the tenant, bounded like the expiry run: nothing is locked while the members are found, and each
     * member is announced in a short transaction of its own.
     */
    public Result runOnce() {
        Optional<ProgramView> program = programReader.program();
        if (program.isEmpty() || !program.get().announcesExpiry()) {
            return Result.NOTHING;
        }
        int noticeDays = program.get().expiryNoticeDays();
        Instant now = clock.now();
        Instant until = now.plus(noticeDays, ChronoUnit.DAYS);
        List<UUID> holderIds = unitOfWork.transactional(() -> expiry.noticeMemberIds(now, until, batchSize));
        int announced = 0;
        int lots = 0;
        long points = 0L;
        for (UUID memberId : holderIds) {
            Optional<Announced> results = unitOfWork.transactional(() -> announce(memberId, now, until, noticeDays));
            if (results.isEmpty()) {
                continue;
            }
            announced++;
            lots += results.get().lots();
            points += results.get().points();
        }
        return new Result(announced, lots, points);
    }

    /** One member's short transaction: the member lock, then its lots, then the single notice about them. */
    private Optional<Announced> announce(UUID memberId, Instant now, Instant until, int noticeDays) {
        TenantId tenantId = tenantContext.requireTenant();
        Optional<MemberRow> locked = members.lockById(memberId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MemberRow member = locked.get();
        if (member.status() == MemberStatus.ERASED) {
            return Optional.empty();
        }
        List<DueLot> due = expiry.lockNoticeLots(memberId, now, until).stream()
                .filter(lot -> lot.remaining() > 0)
                .sorted(Comparator.comparing(DueLot::expiresAt).thenComparing(DueLot::lotId))
                .toList();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        expiry.markNoticed(due.stream().map(DueLot::lotId).toList(), now);
        long available = ledger.liveBalance(memberId, now) - member.debt();
        events.pointsExpiring(tenantId, now, memberId, member.externalRef(), due, noticeDays, available);
        return Optional.of(new Announced(
                due.size(), due.stream().mapToLong(DueLot::remaining).sum()));
    }
}
