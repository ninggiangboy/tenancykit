package dev.ngb.email.audience.application.receiver.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.email.audience.domain.receiver.Receiver;
import java.util.Map;
import java.util.UUID;

/** Changes names and attributes by id; the address and the consent status are not editable here. */
public class UpdateReceiverUseCase {

    public record Change(String firstName, String lastName, Map<String, Object> attributes) {}

    private final UnitOfWork unitOfWork;
    private final ReceiverRepository receivers;
    private final Clock clock;

    public UpdateReceiverUseCase(UnitOfWork unitOfWork, ReceiverRepository receivers, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.receivers = receivers;
        this.clock = clock;
    }

    public Receiver execute(UUID receiverId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Receiver current = receivers.findById(receiverId)
                    .orElseThrow(() -> new NotFoundException("No receiver exists here."));
            expected.check(current.version());
            Receiver updated =
                    current.merge(change.firstName(), change.lastName(), change.attributes(), clock.now());
            if (!receivers.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
