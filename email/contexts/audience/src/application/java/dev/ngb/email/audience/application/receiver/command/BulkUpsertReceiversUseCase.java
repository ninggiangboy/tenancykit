package dev.ngb.email.audience.application.receiver.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.email.audience.domain.receiver.Receiver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Upserts up to 1,000 receivers, reporting a per-item result. One bad address is an error for its item, not for the
 * call: a tenant importing a list must not lose the thousand rows around a typo.
 */
public class BulkUpsertReceiversUseCase {

    public static final int MAX_ITEMS = 1_000;

    public record Item(String email, String firstName, String lastName, Map<String, Object> attributes) {}

    /** @param receiverId the stored receiver, or null when the item was refused; {@code error} then explains it */
    public record Result(int index, UUID receiverId, String error) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ReceiverRepository receivers;
    private final IdGenerator ids;
    private final Clock clock;

    public BulkUpsertReceiversUseCase(
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

    public List<Result> execute(List<Item> items) {
        if (items == null || items.size() > MAX_ITEMS) {
            throw new BadRequestException("a bulk call carries at most " + MAX_ITEMS + " receivers");
        }
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            List<Result> results = new ArrayList<>(items.size());
            for (int index = 0; index < items.size(); index++) {
                Item item = items.get(index);
                try {
                    Receiver candidate = Receiver.create(
                            ids.newId(), tenantId, item.email(), item.firstName(), item.lastName(),
                            item.attributes(), clock.now());
                    Receiver stored = receivers.save(candidate);
                    results.add(new Result(index, stored.id(), null));
                } catch (BadRequestException | IllegalArgumentException refused) {
                    results.add(new Result(index, null, refused.getMessage()));
                }
            }
            return results;
        });
    }
}
