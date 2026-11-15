package dev.ngb.mailroom.audience.application.suppression.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.mailroom.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.mailroom.audience.domain.receiver.NormalizedAddress;
import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;

/**
 * Removes a suppression and, when the address is a known receiver, re-subscribes it. The Tenant asserts the
 * customer consented; this is the explicit action, never a side effect of an import.
 */
public class DeleteSuppressionUseCase {

    private final UnitOfWork unitOfWork;
    private final SuppressionRepository suppressions;
    private final ReceiverRepository receivers;
    private final Clock clock;

    public DeleteSuppressionUseCase(
            UnitOfWork unitOfWork,
            SuppressionRepository suppressions,
            ReceiverRepository receivers,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.suppressions = suppressions;
        this.receivers = receivers;
        this.clock = clock;
    }

    public void execute(String email) {
        String normalized = NormalizedAddress.normalize(email);
        unitOfWork.transactional(() -> {
            if (!suppressions.delete(normalized)) {
                throw new NotFoundException("That address is not suppressed.");
            }
            receivers.findByAddress(normalized).ifPresent(receiver -> {
                if (receiver.status() != ReceiverStatus.SUBSCRIBED) {
                    receivers.update(receiver.subscribe(clock.now()), receiver.version());
                }
            });
            return null;
        });
    }
}
