package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The audience listing endpoints, read inside a tenant-scoped transaction. */
public class AudienceQueries {

    private final UnitOfWork unitOfWork;
    private final AudienceReadRepository audience;

    public AudienceQueries(UnitOfWork unitOfWork, AudienceReadRepository audience) {
        this.unitOfWork = unitOfWork;
        this.audience = audience;
    }

    public Page<ReceiverView> receivers(ListQuery query) {
        return unitOfWork.transactional(() -> audience.listReceivers(query));
    }

    public ReceiverView receiver(UUID receiverId) {
        return unitOfWork.transactional(() -> audience.findReceiver(receiverId)
                .orElseThrow(() -> new NotFoundException("No receiver exists here.")));
    }

    public Page<GroupView> groups(ListQuery query) {
        return unitOfWork.transactional(() -> audience.listGroups(query));
    }

    public GroupView group(UUID groupId) {
        return unitOfWork.transactional(
                () -> audience.findGroup(groupId).orElseThrow(() -> new NotFoundException("No group exists here.")));
    }

    public Page<ReceiverView> members(UUID groupId, ListQuery query) {
        return unitOfWork.transactional(() -> {
            audience.findGroup(groupId).orElseThrow(() -> new NotFoundException("No group exists here."));
            return audience.listGroupReceivers(groupId, query);
        });
    }

    public Page<SuppressionView> suppressions(ListQuery query) {
        return unitOfWork.transactional(() -> audience.listSuppressions(query));
    }
}
