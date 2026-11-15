package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;
import java.util.List;
import java.util.UUID;

/** The audience listing endpoints, read inside a tenant-scoped transaction. */
public class AudienceQueries {

    public static final int DEFAULT_LIMIT = 50;

    private final UnitOfWork unitOfWork;
    private final AudienceReadRepository audience;

    public AudienceQueries(UnitOfWork unitOfWork, AudienceReadRepository audience) {
        this.unitOfWork = unitOfWork;
        this.audience = audience;
    }

    public List<ReceiverView> receivers(String status, String contains, UUID after, Integer limit) {
        ReceiverStatus filter = status == null || status.isBlank() ? null : ReceiverStatus.valueOf(status);
        return unitOfWork.transactional(() -> audience.listReceivers(
                filter, contains, after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public ReceiverView receiver(UUID receiverId) {
        return unitOfWork.transactional(() -> audience.findReceiver(receiverId)
                .orElseThrow(() -> new NotFoundException("No receiver exists here.")));
    }

    public List<GroupView> groups(UUID after, Integer limit) {
        return unitOfWork.transactional(
                () -> audience.listGroups(after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }

    public GroupView group(UUID groupId) {
        return unitOfWork.transactional(
                () -> audience.findGroup(groupId).orElseThrow(() -> new NotFoundException("No group exists here.")));
    }

    public List<ReceiverView> members(UUID groupId, UUID after, Integer limit) {
        return unitOfWork.transactional(() -> {
            audience.findGroup(groupId).orElseThrow(() -> new NotFoundException("No group exists here."));
            return audience.pageGroupReceivers(groupId, after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit));
        });
    }

    public List<SuppressionView> suppressions(String after, Integer limit) {
        return unitOfWork.transactional(
                () -> audience.listSuppressions(after, PageSize.of(limit == null ? DEFAULT_LIMIT : limit)));
    }
}
