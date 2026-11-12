package dev.ngb.platform.access.application.user.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** The read side of users and their effective access. */
public class UserQueries {

    private final UnitOfWork unitOfWork;
    private final UserReadRepository users;

    public UserQueries(UnitOfWork unitOfWork, UserReadRepository users) {
        this.unitOfWork = unitOfWork;
        this.users = users;
    }

    public List<UserView> list(UUID tenantId) {
        return unitOfWork.transactional(() -> users.list(tenantId));
    }

    public List<UserAccessView> pageAccess(UUID after, int limit) {
        return unitOfWork.transactional(() -> users.pageAccess(after, limit));
    }
}
