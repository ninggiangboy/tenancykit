package dev.ngb.loyalty.points.application.member.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.member.MemberErasureParticipant;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.port.LiveLot;
import dev.ngb.loyalty.points.application.member.port.ErasureRequestRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.ErasureRefHash;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRepository;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import dev.ngb.loyalty.points.domain.member.ErasureRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /members/{externalRef}/erasure}: one call cuts every tie this tenant's loyalty holds to a person, and a
 * retry of that call answers the same thing instead of erasing again.
 *
 * <p>In one transaction, under the member's lock: the reference and the display name are cleared, every unused voucher is
 * voided and its stock returned by the context that owns vouchers, every lot that still holds points is written off with
 * one {@code ERASE} entry, the record of the request is appended and {@code member.erased} goes to the outbox. Everything
 * that pointed at the member — its orders, its earn records, its lots, its ledger and its redemptions — stays as an
 * anonymous record keyed by the opaque member id.
 *
 * <p>A retry is answered from the record of the request, which is keyed by the hash of the reference rather than the
 * reference: the reference the caller sent is free again and may already name another member, so a retried call must find
 * its own record before it looks at any member. Only a reference this tenant never used, for a member it does not hold, is
 * a {@code 404}.
 */
public class EraseMemberUseCase {

    /** The erasure and what it left: the anonymous member, when it happened and how many vouchers it stopped. */
    public record Result(UUID memberId, Instant erasedAt, long vouchersVoided, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final MemberRepository members;
    private final ErasureRequestRepository requests;
    private final LedgerRepository ledger;
    private final RedemptionRepository redemptions;
    private final List<MemberErasureParticipant> participants;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public EraseMemberUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            ErasureRequestRepository requests,
            LedgerRepository ledger,
            RedemptionRepository redemptions,
            List<MemberErasureParticipant> participants,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.requests = requests;
        this.ledger = ledger;
        this.redemptions = redemptions;
        this.participants = List.copyOf(participants);
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(String externalRef) {
        String ref = MemberRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> erase(ref));
    }

    private Result erase(String externalRef) {
        TenantId tenantId = tenantContext.requireTenant();
        String refHash = ErasureRefHash.of(tenantId, externalRef);
        Optional<ErasureRequest> performed = requests.findByRefHash(refHash);
        if (performed.isPresent()) {
            return replay(performed.get());
        }
        // The member is locked before anything about it changes, so two calls that do mean to erase the same person run
        // one after the other rather than over each other.
        Optional<MemberRow> locked = members.lockByRef(externalRef);
        if (locked.isEmpty()) {
            // A concurrent retry of the same call erased the member while this one waited for the lock.
            return replay(requests.findByRefHash(refHash).orElseThrow(EraseMemberUseCase::memberNotFound));
        }
        MemberRow member = locked.get();
        // Microseconds are what the stored instant can hold, so the answer a retry replays is the answer this call gives.
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        members.markErased(member.id());
        // The Tenant's own reference on a spend is free text and can name the person; the spend itself stays as an
        // anonymous redemption keyed by the opaque member id.
        redemptions.clearMemberReferences(member.id());
        long vouchersVoided = 0L;
        for (MemberErasureParticipant participant : participants) {
            vouchersVoided += participant.erase(member.id());
        }
        releaseLots(member.id(), now);
        ErasureRequest request = new ErasureRequest(tenantId, refHash, member.id(), now);
        if (!requests.insertIfAbsent(request)) {
            // The record of this reference already exists, so that record is the answer rather than this call's instant.
            return replay(requests.findByRefHash(refHash).orElseThrow(EraseMemberUseCase::memberNotFound));
        }
        events.memberErased(tenantId, now, member.id());
        return new Result(member.id(), now, vouchersVoided, false);
    }

    /**
     * Writes off every lot that still holds points, expired or not, with one {@code ERASE} entry each and an allocation
     * recording what it took: the lot rows stay as the anonymous history of what the member once held.
     */
    private void releaseLots(UUID memberId, Instant now) {
        for (LiveLot lot : ledger.lotsHolding(memberId)) {
            if (!ledger.zeroLot(lot.lotId())) {
                // Another flow emptied the lot between the read and the write; there is nothing left to release.
                continue;
            }
            UUID entryId = ids.newId();
            ledger.addEntry(new LedgerEntryRow(
                    entryId, memberId, LedgerKind.ERASE, lot.remaining(), lot.orderId(), null, null, now));
            ledger.addAllocation(entryId, lot.lotId(), lot.remaining());
        }
    }

    private Result replay(ErasureRequest performed) {
        long vouchersVoided = 0L;
        for (MemberErasureParticipant participant : participants) {
            vouchersVoided += participant.erasedRecords(performed.memberId());
        }
        return new Result(performed.memberId(), performed.erasedAt(), vouchersVoided, true);
    }

    private static PointsRefusal memberNotFound() {
        return PointsRefusal.notFound("MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant.");
    }
}
