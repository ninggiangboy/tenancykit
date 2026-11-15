package dev.ngb.email.audience.application.receiver.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.email.audience.domain.receiver.Receiver;
import java.util.UUID;

/**
 * Erases a receiver and their memberships (a customer's deletion request). The suppression row is deliberately kept,
 * so an address that is deleted and later re-imported is still not mailed.
 */
public class DeleteReceiverUseCase {

    private final UnitOfWork unitOfWork;
    private final ReceiverRepository receivers;

    public DeleteReceiverUseCase(UnitOfWork unitOfWork, ReceiverRepository receivers) {
        this.unitOfWork = unitOfWork;
        this.receivers = receivers;
    }

    public void execute(UUID receiverId, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            Receiver current = receivers.findById(receiverId)
                    .orElseThrow(() -> new NotFoundException("No receiver exists here."));
            expected.check(current.version());
            if (!receivers.delete(receiverId)) {
                throw new PreconditionFailedException(null);
            }
            return null;
        });
    }
}
