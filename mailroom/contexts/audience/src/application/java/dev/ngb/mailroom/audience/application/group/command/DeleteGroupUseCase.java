package dev.ngb.mailroom.audience.application.group.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.audience.application.group.port.GroupRepository;
import dev.ngb.mailroom.audience.domain.group.ReceiverGroup;
import java.util.UUID;

/** Deletes the group and its memberships; the receivers stay. */
public class DeleteGroupUseCase {

    private final UnitOfWork unitOfWork;
    private final GroupRepository groups;

    public DeleteGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups) {
        this.unitOfWork = unitOfWork;
        this.groups = groups;
    }

    public void execute(UUID groupId, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            ReceiverGroup current = groups.findById(groupId)
                    .orElseThrow(() -> new NotFoundException("No group exists here."));
            expected.check(current.version());
            if (!groups.delete(groupId)) {
                throw new PreconditionFailedException(null);
            }
            return null;
        });
    }
}
