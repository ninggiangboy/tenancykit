package dev.ngb.platform.access.application.role.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

public class RoleQueries {

    private final UnitOfWork unitOfWork;
    private final RoleReadRepository roles;

    public RoleQueries(UnitOfWork unitOfWork, RoleReadRepository roles) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
    }

    public List<RoleView> list(UUID tenantId) {
        return unitOfWork.transactional(() -> roles.list(tenantId));
    }
}
