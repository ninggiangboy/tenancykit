package dev.ngb.booking.catalog.application.group.command;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.catalog.exception.DuplicateCodeException;
import dev.ngb.booking.catalog.application.group.port.GroupRepository;
import dev.ngb.booking.catalog.application.group.service.GroupRules;
import dev.ngb.booking.catalog.domain.group.GroupId;
import dev.ngb.booking.catalog.domain.group.GroupMember;
import dev.ngb.booking.catalog.domain.group.GroupStrategy;
import dev.ngb.booking.catalog.domain.group.ResourceGroup;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;

/**
 * Creates a resource group. The code is the Tenant's own and unique per tenant, and every member must name a resource
 * the tenant already holds and has not archived.
 */
public class CreateGroupUseCase {

    /** A group as the Tenant sent it; a missing combine cap and a missing member list are the defaults. */
    public record Command(
            String code, String name, GroupStrategy strategy, Integer maxCombine, List<GroupMember> members) {}

    private final UnitOfWork unitOfWork;
    private final GroupRepository groups;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateGroupUseCase(
            UnitOfWork unitOfWork,
            GroupRepository groups,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public ResourceGroup execute(Command command) {
        String code = GroupRules.code(command.code());
        String name = GroupRules.name(command.name());
        GroupStrategy strategy = GroupRules.strategy(command.strategy());
        int maxCombine = GroupRules.maxCombine(command.maxCombine());
        List<GroupMember> members = GroupRules.members(command.members());
        return unitOfWork.transactional(() -> {
            if (groups.existsByCode(code)) {
                throw new DuplicateCodeException(code);
            }
            if (!members.isEmpty() && !groups.resourcesExistAndAreActive(GroupRules.resourceIds(members))) {
                throw new CatalogValidationException(
                        "a group member names a resource that does not exist here or is archived");
            }
            ResourceGroup group = ResourceGroup.create(
                    new GroupId(ids.newId()),
                    tenantContext.requireTenant(),
                    code,
                    name,
                    strategy,
                    maxCombine,
                    members,
                    clock.now());
            groups.insert(group);
            return group;
        });
    }
}
