package dev.ngb.loyalty.points.application.redemption.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.redemption.PointSpendRefusal;
import dev.ngb.loyalty.points.api.redemption.PointSpender;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.port.LiveLot;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRepository;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRow;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spends a member's points once, however often the caller sends the same request.
 *
 * <p>The order of the steps is the order of the locks: the member's row is locked first, so two spends of one member run
 * one after the other; the idempotency key is read only after that lock, so the second caller sees what the first
 * committed instead of both deciding the key is free. A debt is refused before anything is written, and the lots are
 * consumed by earliest expiry and then by id, which is the order the next point of the member would have expired in.
 *
 * <p>It runs inside the transaction the caller opened. The web endpoint opens one for the single spend it performs; a
 * caller that spends as part of a larger change keeps its own, so the spend and that change commit or roll back together.
 */
public class PointSpenderService implements PointSpender {

    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final RedemptionRepository redemptions;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public PointSpenderService(
            MemberRepository members,
            LedgerRepository ledger,
            RedemptionRepository redemptions,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.members = members;
        this.ledger = ledger;
        this.redemptions = redemptions;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Receipt spend(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        String externalRef = MemberRules.externalRef(command.externalRef());
        Instant now = clock.now();
        String fingerprint = Fingerprint.of(externalRef, command.points(), command.reference());
        MemberRow member = members
                .lockByRef(externalRef)
                .orElseThrow(() -> new PointSpendRefusal(
                        PointSpendRefusal.Kind.NOT_FOUND,
                        "MEMBER_NOT_FOUND",
                        "No live member holds that externalRef in this tenant."));
        Optional<RedemptionRow> stored = redemptions.findByIdempotencyKey(command.idempotencyKey());
        if (stored.isPresent()) {
            return replay(stored.get(), member, fingerprint, command);
        }
        requireRedeemable(member, command.points(), now);

        UUID redemptionId = ids.newId();
        RedemptionRow redemption = RedemptionRow.active(
                redemptionId,
                member.id(),
                command.idempotencyKey(),
                fingerprint,
                command.points(),
                command.reference(),
                now);
        if (!redemptions.add(redemption)) {
            // Another member's call committed the same key between this call's lock and its insert.
            RedemptionRow winner = redemptions
                    .findByIdempotencyKey(command.idempotencyKey())
                    .orElseThrow(PointSpenderService::keyVanished);
            return replay(winner, member, fingerprint, command);
        }
        consume(member, command.points(), redemptionId, now);
        long debt = member.debt();
        long available = ledger.liveBalance(member.id(), now) - debt;
        events.pointsRedeemed(tenantId, now, member.id(), member.externalRef(), redemptionId, command.points(),
                redemption.reference(), available, debt);
        return receipt(redemption, member, available, debt, false);
    }

    /** Refuses a spend the member cannot make, before a row is written. */
    private void requireRedeemable(MemberRow member, long points, Instant now) {
        if (member.debt() > 0) {
            throw new PointSpendRefusal(
                    PointSpendRefusal.Kind.CONFLICT,
                    "MEMBER_IN_DEBT",
                    "The member still owes points, which the next earn repays first.");
        }
        long available = ledger.liveBalance(member.id(), now) - member.debt();
        if (points > available) {
            throw new PointSpendRefusal(
                    PointSpendRefusal.Kind.CONFLICT,
                    "INSUFFICIENT_POINTS",
                    "The member does not hold that many unexpired points.");
        }
    }

    /** Takes the points from the lots that expire soonest, recording what each one gave. */
    private void consume(MemberRow member, long points, UUID redemptionId, Instant now) {
        UUID entryId = ids.newId();
        ledger.addEntry(new LedgerEntryRow(
                entryId, member.id(), LedgerKind.REDEEM, points, null, null, redemptionId, now));
        long outstanding = points;
        for (LiveLot lot : ledger.liveLots(member.id(), now)) {
            if (outstanding == 0) {
                break;
            }
            long taken = Math.min(lot.remaining(), outstanding);
            ledger.consume(lot.lotId(), taken);
            ledger.addAllocation(entryId, lot.lotId(), taken);
            outstanding -= taken;
        }
        if (outstanding > 0) {
            throw new IllegalStateException("the lots could not cover a spend the balance said they cover");
        }
    }

    private Receipt replay(RedemptionRow stored, MemberRow member, String fingerprint, Command command) {
        if (!stored.fingerprint().equals(fingerprint) || !stored.memberId().equals(member.id())) {
            throw new PointSpendRefusal(
                    PointSpendRefusal.Kind.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "The same Idempotency-Key was sent before with a different body.");
        }
        long available = ledger.liveBalance(member.id(), clock.now()) - member.debt();
        return receipt(stored, member, available, member.debt(), true);
    }

    private static Receipt receipt(
            RedemptionRow redemption, MemberRow member, long available, long debt, boolean replayed) {
        return new Receipt(
                redemption.id(),
                member.id(),
                member.externalRef(),
                redemption.points(),
                redemption.reference(),
                redemption.status().name(),
                available,
                debt,
                redemption.createdAt(),
                redemption.reversedAt(),
                replayed);
    }

    private static PointSpendRefusal keyVanished() {
        return new PointSpendRefusal(
                PointSpendRefusal.Kind.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "The Idempotency-Key was used by a request that has since rolled back; send it again.");
    }
}
