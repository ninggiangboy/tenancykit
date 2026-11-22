package dev.ngb.mailroom.audience.application.receiver.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.mailroom.audience.domain.receiver.Receiver;
import java.util.Map;

/**
 * Upserts a receiver by address. A second call with the same address updates names and attributes and never
 * re-subscribes the address: that is an explicit action, not a side effect of an import.
 */
public class UpsertReceiverUseCase {

    public record Command(String email, String firstName, String lastName, Map<String, Object> attributes) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ReceiverRepository receivers;
    private final IdGenerator ids;
    private final Clock clock;

    public UpsertReceiverUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ReceiverRepository receivers,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.receivers = receivers;
        this.ids = ids;
        this.clock = clock;
    }

    public Receiver execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        Receiver candidate = Receiver.create(
                ids.newId(), tenantId, command.email(), command.firstName(), command.lastName(),
                command.attributes(), clock.now());
        return unitOfWork.transactional(() -> receivers.save(candidate));
    }
}
