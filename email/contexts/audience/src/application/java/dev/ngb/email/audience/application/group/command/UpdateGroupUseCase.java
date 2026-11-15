package dev.ngb.email.audience.application.group.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.audience.application.group.port.GroupRepository;
import dev.ngb.email.audience.domain.group.ReceiverGroup;
import java.util.UUID;

/** Renames a group, guarded by {@code If-Match}. */
public class UpdateGroupUseCase {

    private final UnitOfWork unitOfWork;
    private final GroupRepository groups;
    private final Clock clock;

    public UpdateGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
        this.clock = clock;
    }

    public ReceiverGroup execute(UUID groupId, String name, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            ReceiverGroup current = groups.findById(groupId)
                    .orElseThrow(() -> new NotFoundException("No group exists here."));
            expected.check(current.version());
            ReceiverGroup updated = current.rename(name, clock.now());
            if (!groups.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
