package dev.ngb.loyalty.points.application.redemption.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.port.LotAllocation;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRepository;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionReversalHook;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRow;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import dev.ngb.loyalty.points.domain.redemption.RedemptionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Restores the lots a redemption consumed and marks it reversed.
 *
 * <p>The member's row is locked first and the redemption second, which is the order every write of this context takes,
 * so a reversal can never run beside the spend it undoes. An already reversed redemption answers what it holds and
 * writes nothing: the reversal is idempotent without a key of its own.
 *
 * <p>Every allocation goes back to the lot it came from. A lot that expired in the meantime takes the points back and
 * stays expired, because the balance counts only lots that expire in the future: the points are recorded as restored and
 * are never spendable again.
 */
public class ReverseRedemptionUseCase {

    /** One reversal as the caller asks for it: the member's reference and the redemption loyalty answered with. */
    public record Command(String externalRef, UUID redemptionId) {}

    /** The redemption after the reversal and the balance it left; {@code replayed} is true when it was already reversed. */
    public record Result(
            UUID redemptionId,
            String externalRef,
            long points,
            String reference,
            String status,
            long available,
            long debt,
            Instant createdAt,
            Instant reversedAt,
            boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final MemberRepository members;
    private final RedemptionRepository redemptions;
    private final LedgerRepository ledger;
    private final RedemptionReversalHook hook;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ReverseRedemptionUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            RedemptionRepository redemptions,
            LedgerRepository ledger,
            RedemptionReversalHook hook,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.redemptions = redemptions;
        this.ledger = ledger;
        this.hook = hook;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String externalRef = MemberRules.externalRef(command.externalRef());
        if (command.redemptionId() == null) {
            throw PointsRefusal.invalid("The redemption's id is required.");
        }
        UUID redemptionId = command.redemptionId();
        return unitOfWork.transactional(() -> reverse(externalRef, redemptionId));
    }

    private Result reverse(String externalRef, UUID redemptionId) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        MemberRow member = members.lockByRef(externalRef).orElseThrow(ReverseRedemptionUseCase::memberNotFound);
        RedemptionRow redemption = redemptions
                .lockById(redemptionId)
                .filter(found -> found.memberId().equals(member.id()))
                .orElseThrow(ReverseRedemptionUseCase::redemptionNotFound);
        if (redemption.reversed()) {
            return result(redemption, member, now, true);
        }
        hook.beforeReversal(member.id(), redemptionId);
        UUID entryId = ledger.entryOfRedemption(redemptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "redemption " + redemptionId + " has no ledger entry to reverse"));
        ledger.addEntry(new LedgerEntryRow(
                ids.newId(), member.id(), LedgerKind.REDEEM_REVERSAL, redemption.points(), null, null, redemptionId,
                now));
        for (LotAllocation allocation : ledger.allocations(entryId)) {
            ledger.restore(allocation.lotId(), allocation.points());
        }
        redemptions.markReversed(redemptionId, now);
        long available = ledger.liveBalance(member.id(), now) - member.debt();
        events.pointsRedemptionReversed(tenantId, now, member.id(), member.externalRef(), redemptionId,
                redemption.points(), redemption.reference(), available, member.debt());
        return new Result(
                redemption.id(),
                member.externalRef(),
                redemption.points(),
                redemption.reference(),
                RedemptionStatus.REVERSED.name(),
                available,
                member.debt(),
                redemption.createdAt(),
                now,
                false);
    }

    private Result result(RedemptionRow redemption, MemberRow member, Instant now, boolean replayed) {
        return new Result(
                redemption.id(),
                member.externalRef(),
                redemption.points(),
                redemption.reference(),
                redemption.status().name(),
                ledger.liveBalance(member.id(), now) - member.debt(),
                member.debt(),
                redemption.createdAt(),
                redemption.reversedAt(),
                replayed);
    }

    private static PointsRefusal memberNotFound() {
        return PointsRefusal.notFound("MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant.");
    }

    private static PointsRefusal redemptionNotFound() {
        return PointsRefusal.notFound("REDEMPTION_NOT_FOUND", "This member made no redemption with that id.");
    }
}
