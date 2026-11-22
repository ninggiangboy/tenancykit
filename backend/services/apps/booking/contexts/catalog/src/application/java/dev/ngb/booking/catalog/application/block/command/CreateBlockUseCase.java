package dev.ngb.booking.catalog.application.block.command;

import dev.ngb.booking.catalog.application.block.port.BlockRepository;
import dev.ngb.booking.catalog.application.block.service.BlockRules;
import dev.ngb.booking.catalog.domain.block.Block;
import dev.ngb.booking.catalog.domain.block.BlockId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Closes a period on a resource: one range, or a rule with the first occurrence and its length.
 *
 * <p>A block never deletes a booking. The answer names the bookings the closed period overlaps, which is empty while
 * no booking exists, so a Tenant decides for itself what to do about the ones it hears about.
 */
public class CreateBlockUseCase {

    /** A block as the Tenant sent it; {@code rule} is empty for a one-off range. */
    public record Command(UUID resourceId, String reason, Instant startsAt, Instant endsAt, List<String> rule) {}

    /** The block and the bookings it overlaps. */
    public record Result(Block block, List<UUID> overlappingBookings) {}

    private final UnitOfWork unitOfWork;
    private final BlockRepository repository;
    private final BlockRules rules;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateBlockUseCase(
            UnitOfWork unitOfWork,
            BlockRepository repository,
            BlockRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.rules = rules;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String reason = BlockRules.reason(command.reason());
        return unitOfWork.transactional(() -> {
            String zone = rules.zoneOf(command.resourceId());
            List<String> rule =
                    rules.recurrenceLines(command.rule(), zone, command.startsAt(), command.endsAt());
            Block block = Block.create(
                    new BlockId(ids.newId()),
                    tenantContext.requireTenant(),
                    command.resourceId(),
                    reason,
                    command.startsAt(),
                    command.endsAt(),
                    rule,
                    clock.now());
            repository.insert(block);
            return new Result(block, List.of());
        });
    }
}
