package dev.ngb.loyalty.points.application.member.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Enrols a member ahead of its first order, keyed by the Tenant's reference.
 *
 * <p>It is idempotent by that reference: a reference that is already live answers the member that holds it and announces
 * nothing, because no member was enrolled. An erased member released its reference, so the same reference later starts
 * a new member and announces that one.
 */
public class EnrolMemberUseCase {

    public record Command(String externalRef, String displayName) {}

    /** The member and whether this call created it, which decides 201 against 200. */
    public record Result(UUID memberId, String externalRef, String displayName, Instant enrolledAt, boolean created) {}

    private final UnitOfWork unitOfWork;
    private final MemberRepository members;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public EnrolMemberUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String externalRef = MemberRules.externalRef(command.externalRef());
        String displayName = MemberRules.displayName(command.displayName());
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            Instant now = clock.now();
            Optional<MemberRow> existing = members.lockByRef(externalRef);
            if (existing.isPresent()) {
                return result(existing.get(), false);
            }
            MemberRow created = MemberRow.enrolled(ids.newId(), externalRef, displayName, now);
            if (!members.addIfAbsent(created)) {
                // Another call enrolled the same reference between the lock and the insert: answer that member.
                return result(members.lockByRef(externalRef).orElseThrow(EnrolMemberUseCase::vanished), false);
            }
            events.memberEnrolled(tenantId, created.id(), externalRef, now, false);
            return result(created, true);
        });
    }

    private static Result result(MemberRow member, boolean created) {
        return new Result(member.id(), member.externalRef(), member.displayName(), member.enrolledAt(), created);
    }

    private static IllegalStateException vanished() {
        return new IllegalStateException("the member row was inserted and then not found");
    }
}
