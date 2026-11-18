package dev.ngb.mailroom.audience.application.group.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.audience.application.group.port.GroupRepository;
import dev.ngb.mailroom.audience.application.group.port.MembershipRepository;
import dev.ngb.mailroom.audience.application.receiver.port.ReceiverRepository;
import java.util.List;
import java.util.UUID;

/** Adds and removes up to 1,000 members at a time, refusing a receiver that belongs to another tenant. */
public class ManageMembersUseCase {

    public static final int MAX_IDS = 1_000;

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final GroupRepository groups;
    private final ReceiverRepository receivers;
    private final MembershipRepository members;
    private final Clock clock;

    public ManageMembersUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            GroupRepository groups,
            ReceiverRepository receivers,
            MembershipRepository members,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.groups = groups;
        this.receivers = receivers;
        this.members = members;
        this.clock = clock;
    }

    public int add(UUID groupId, List<UUID> receiverIds) {
        validate(receiverIds);
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            groups.findById(groupId).orElseThrow(() -> new NotFoundException("No group exists here."));
            List<UUID> owned = receivers.existingIds(receiverIds);
            return members.add(tenantId, groupId, owned, clock.now());
        });
    }

    public int remove(UUID groupId, List<UUID> receiverIds) {
        validate(receiverIds);
        return unitOfWork.transactional(() -> {
            groups.findById(groupId).orElseThrow(() -> new NotFoundException("No group exists here."));
            return members.remove(groupId, receiverIds);
        });
    }

    private static void validate(List<UUID> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty() || receiverIds.size() > MAX_IDS) {
            throw new BadRequestException("a membership call carries between 1 and " + MAX_IDS + " receiver ids");
        }
    }
}
