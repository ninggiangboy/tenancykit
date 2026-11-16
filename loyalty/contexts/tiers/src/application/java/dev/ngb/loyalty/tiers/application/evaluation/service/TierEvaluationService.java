package dev.ngb.loyalty.tiers.application.evaluation.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.tier.MemberTiers;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.api.tier.TierListRevisions;
import dev.ngb.loyalty.program.api.tier.TierView;
import dev.ngb.loyalty.tiers.domain.policy.TierPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides a member's tier over the spend its orders earned, raises it in the transaction of the earn that crossed a
 * threshold, and lowers it only in the scheduled evaluation.
 *
 * <p>Two decisions per member, from the same policy: the earn path may only raise, so a member never drops a tier because
 * an order was returned, and the scheduled pass may do either, so a tier the program made unreachable or a window that
 * slid past a member's orders is corrected once a day rather than never. A return that puts the member below the tier it
 * holds asks for that pass right away by moving the member's review instant to now.
 *
 * <p>The member row is the first lock of every path, taken before the spend is read, so a rise and a fall for one member
 * cannot each read the same spend and write their own tiers. Each member of the scheduled pass commits alone, so one
 * member's failure leaves the others done.
 */
public class TierEvaluationService {

    /** A tier rose. */
    public static final String UP = "UP";

    /** A tier fell. */
    public static final String DOWN = "DOWN";

    /** What one pass over a tenant did: how many members it looked at and how many of them changed tier. */
    public record Report(int reviewed, int changed) {}

    private final UnitOfWork unitOfWork;
    private final MemberTiers members;
    private final ProgramReader programs;
    private final TierListRevisions tierList;
    private final TierEvents events;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final int batchSize;

    public TierEvaluationService(
            UnitOfWork unitOfWork,
            MemberTiers members,
            ProgramReader programs,
            TierListRevisions tierList,
            TierEvents events,
            TenantContext tenantContext,
            Clock clock,
            int batchSize) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.programs = programs;
        this.tierList = tierList;
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Runs inside the earn's transaction: a crossed threshold moves the member up before the earn commits. */
    public void afterEarn(UUID memberId) {
        observe(memberId);
    }

    /**
     * Runs inside the return's transaction: the tier the member holds stands, and a return that took the spend below it
     * moves the member's review to now so the next evaluation decides.
     */
    public void afterReturn(UUID memberId) {
        observe(memberId);
    }

    /** The spend the member still needs to reach {@code minSpend}, or zero when it has reached it. */
    public long remainingSpend(UUID memberId, long minSpend) {
        return unitOfWork.transactional(() -> {
            Optional<ProgramView> program = programs.program();
            if (program.isEmpty()) {
                return 0L;
            }
            ProgramView rules = program.get();
            TierPolicy policy = new TierPolicy(rules.tierWindowMonths(), rules.timeZone());
            Instant now = clock.now();
            long qualifying = policy.qualifyingSpend(spend(memberId, policy, now), now);
            return Math.max(0L, minSpend - qualifying);
        });
    }

    /**
     * One pass over the tenant: first takes a changed tier list to every member, then decides every member whose review
     * is due, in id-ordered batches and one short transaction per member after its lock.
     */
    public Report reviewDue() {
        Instant now = clock.now();
        remarkEveryone(now);
        int reviewed = 0;
        int changed = 0;
        UUID after = null;
        while (true) {
            UUID cursor = after;
            List<UUID> due = unitOfWork.transactional(() -> members.dueForReview(now, cursor, batchSize));
            if (due.isEmpty()) {
                return new Report(reviewed, changed);
            }
            for (UUID memberId : due) {
                reviewed++;
                if (unitOfWork.transactional(() -> review(memberId, now))) {
                    changed++;
                }
            }
            after = due.getLast();
        }
    }

    /**
     * Takes one id-ordered batch of the tenant's members to the review instant {@code at}, and clears the tier list's
     * mark once the last batch is done.
     *
     * @return the cursor to continue from, or empty when the pass has reached every member
     */
    public Optional<UUID> remark(UUID afterId, Instant at) {
        return unitOfWork.transactional(() -> {
            if (!tierList.reviewPending()) {
                return Optional.<UUID>empty();
            }
            List<UUID> batch = members.remarkForReview(at, afterId, batchSize);
            if (batch.size() < batchSize) {
                tierList.reviewed();
                return Optional.<UUID>empty();
            }
            return Optional.of(batch.getLast());
        });
    }

    /** A tier change reached every member once the pass has no cursor left; a run that stopped resumes from the mark. */
    private void remarkEveryone(Instant now) {
        UUID after = null;
        while (true) {
            Optional<UUID> cursor = remark(after, now);
            if (cursor.isEmpty()) {
                return;
            }
            after = cursor.get();
        }
    }

    /** The earn and return path: raise when the spend reached a higher tier, schedule the rest, never lower here. */
    private void observe(UUID memberId) {
        unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Decision decision = decide(memberId, now);
            if (decision == null) {
                return;
            }
            if (decision.rises()) {
                write(decision, now, UP);
            } else if (decision.falls()) {
                moveReviewTo(decision.member().memberId(), decision.member().tierReviewAt(), now);
            } else {
                tighten(decision);
            }
        });
    }

    /** The scheduled pass: the same decision, allowed to lower the member as well as raise it. */
    private boolean review(UUID memberId, Instant now) {
        Decision decision = decide(memberId, now);
        if (decision == null) {
            return false;
        }
        if (decision.rises()) {
            write(decision, now, UP);
            return true;
        }
        if (decision.falls()) {
            write(decision, now, DOWN);
            return true;
        }
        members.setReviewAt(memberId, decision.choice().reviewAt());
        return false;
    }

    /**
     * Decides one member against the program's list as it stands now, under the member's lock.
     *
     * @return null when there is nothing to decide: no member, no program, or a program without tiers
     */
    private Decision decide(UUID memberId, Instant now) {
        Optional<MemberTiers.TierMember> locked = members.lock(memberId);
        Optional<ProgramView> program = programs.program();
        if (locked.isEmpty() || program.isEmpty()) {
            return null;
        }
        List<TierView> tiers = programs.tiers();
        if (tiers.isEmpty()) {
            return null;
        }
        ProgramView rules = program.get();
        TierPolicy policy = new TierPolicy(rules.tierWindowMonths(), rules.timeZone());
        TierPolicy.Choice choice = policy.choose(tiers, spend(memberId, policy, now), now);
        MemberTiers.TierMember member = locked.get();
        String from = held(tiers, member.tierCode());
        return new Decision(member, from, choice, rank(tiers, from), rank(tiers, choice.tierCode()));
    }

    private List<TierPolicy.Spend> spend(UUID memberId, TierPolicy policy, Instant now) {
        return members.spendSince(memberId, policy.windowStart(now)).stream()
                .map(entry -> new TierPolicy.Spend(entry.occurredAt(), entry.amount()))
                .toList();
    }

    /** Writes the new tier and announces it, in the caller's transaction: the tier and its event commit together. */
    private void write(Decision decision, Instant now, String direction) {
        MemberTiers.TierMember member = decision.member();
        members.recordTier(member.memberId(), decision.to(), decision.choice().reviewAt());
        events.tierChanged(tenantContext.requireTenant(), now, member.memberId(), member.externalRef(),
                decision.from(), decision.to(), direction, decision.choice().qualifyingSpend());
    }

    /**
     * Keeps the review at the moment the oldest counted order ages out: earlier than what is stored, or set at all when
     * nothing is stored, so a member that stopped earning is still looked at once its last order leaves the window.
     */
    private void tighten(Decision decision) {
        Instant candidate = decision.choice().reviewAt();
        if (candidate == null) {
            return;
        }
        Instant stored = decision.member().tierReviewAt();
        if (stored == null || candidate.isBefore(stored)) {
            members.setReviewAt(decision.member().memberId(), candidate);
        }
    }

    /** Nobody ever moves a review later through this flow: a member that has to be looked at now stays due now. */
    private void moveReviewTo(UUID memberId, Instant stored, Instant now) {
        if (stored == null || now.isBefore(stored)) {
            members.setReviewAt(memberId, now);
        }
    }

    /** The tier the member holds: the one stored, or the lowest of the list when it holds none yet. */
    private static String held(List<TierView> tiers, String tierCode) {
        if (tierCode != null) {
            for (TierView tier : tiers) {
                if (tier.code().equals(tierCode)) {
                    return tierCode;
                }
            }
        }
        return tiers.getFirst().code();
    }

    /** Where a tier sits in the list: the lowest is zero, and a code the list does not have counts as the lowest. */
    private static int rank(List<TierView> tiers, String tierCode) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).code().equals(tierCode)) {
                return i;
            }
        }
        return 0;
    }

    /** One member's answer: what it holds, what the policy gives it, and how the two compare in the list's order. */
    private record Decision(
            MemberTiers.TierMember member,
            String from,
            TierPolicy.Choice choice,
            int fromRank,
            int toRank) {

        boolean rises() {
            return toRank > fromRank;
        }

        boolean falls() {
            return toRank < fromRank;
        }

        String to() {
            return choice.tierCode();
        }
    }
}
