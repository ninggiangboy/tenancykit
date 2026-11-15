package dev.ngb.email.audience.application.group.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.application.group.port.GroupRepository;
import dev.ngb.email.audience.domain.group.ReceiverGroup;

/** Creates a static group. */
public class CreateGroupUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final GroupRepository groups;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateGroupUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            GroupRepository groups,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.groups = groups;
        this.ids = ids;
        this.clock = clock;
    }

    public ReceiverGroup execute(String name) {
        TenantId tenantId = tenantContext.requireTenant();
        ReceiverGroup group = ReceiverGroup.create(ids.newId(), tenantId, name, clock.now());
        unitOfWork.transactional(() -> groups.insert(group));
        return group;
    }
}
