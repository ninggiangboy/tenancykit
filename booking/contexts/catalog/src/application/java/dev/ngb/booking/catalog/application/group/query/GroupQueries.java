package dev.ngb.booking.catalog.application.group.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's resource groups for the GET handlers. */
public class GroupQueries {

    private final UnitOfWork unitOfWork;
    private final GroupReadRepository groups;

    public GroupQueries(UnitOfWork unitOfWork, GroupReadRepository groups) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
    }

    public List<GroupView> list() {
        return unitOfWork.transactional(() -> groups.list());
    }

    public GroupView get(UUID groupId) {
        return unitOfWork.transactional(() -> groups
                .findById(groupId)
                .orElseThrow(() -> new NotFoundException("No resource group exists here with that id.")));
    }
}
