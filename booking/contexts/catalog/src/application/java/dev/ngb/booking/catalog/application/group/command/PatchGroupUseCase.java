package dev.ngb.booking.catalog.application.group.command;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.group.port.GroupRepository;
import dev.ngb.booking.catalog.application.group.service.GroupRules;
import dev.ngb.booking.catalog.domain.group.GroupMember;
import dev.ngb.booking.catalog.domain.group.GroupStrategy;
import dev.ngb.booking.catalog.domain.group.ResourceGroup;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.List;
import java.util.UUID;

/**
 * {@code PATCH /resource-groups/{id}}: only the properties the caller sent change, and only if the group is still the
 * version the caller read. A member list replaces the stored one in full, because a caller always states the set it
 * wants by the time it is stored.
 */
public class PatchGroupUseCase {

    /** A change request: a null property is left as it is, and a member list replaces the whole set. */
    public record Change(String name, GroupStrategy strategy, Integer maxCombine, List<GroupMember> members) {}

    private final UnitOfWork unitOfWork;
    private final GroupRepository groups;
    private final Clock clock;

    public PatchGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
        this.clock = clock;
    }

    public ResourceGroup execute(UUID groupId, Change change, ExpectedVersion expected) {
        String name = change.name() == null ? null : GroupRules.name(change.name());
        GroupStrategy strategy = change.strategy() == null ? null : GroupRules.strategy(change.strategy());
        Integer maxCombine = change.maxCombine() == null ? null : GroupRules.maxCombine(change.maxCombine());
        List<GroupMember> members = change.members() == null ? null : GroupRules.members(change.members());
        return unitOfWork.transactional(() -> {
            ResourceGroup group = groups
                    .lockById(groupId)
                    .orElseThrow(() -> new NotFoundException("No resource group exists here with that id."));
            expected.check(group.version());
            if (members != null
                    && !members.isEmpty()
                    && !groups.resourcesExistAndAreActive(GroupRules.resourceIds(members))) {
                throw new CatalogValidationException(
                        "a group member names a resource that does not exist here or is archived");
            }
            boolean changed = group.change(
                    name == null ? group.name() : name,
                    strategy == null ? group.strategy() : strategy,
                    maxCombine == null ? group.maxCombine() : maxCombine,
                    members == null ? group.members() : members,
                    clock.now());
            if (changed) {
                groups.update(group);
            }
            return group;
        });
    }
}
