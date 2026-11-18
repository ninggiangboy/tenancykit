package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.port.HousekeepingRepository;
import dev.ngb.notification.dispatch.application.notification.service.HousekeepingSettings;
import java.time.Instant;

/** One bounded purge round for one tenant of the dispatch-owned tables. */
public class HousekeepingUseCase {

    public record Purged(int notifications, int dispatches) {}

    private final UnitOfWork unitOfWork;
    private final HousekeepingRepository repository;
    private final HousekeepingSettings settings;
    private final Clock clock;

    public HousekeepingUseCase(
            UnitOfWork unitOfWork, HousekeepingRepository repository, HousekeepingSettings settings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    public Purged runOnce(TenantId tenantId) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            int notifications = repository.deleteNotificationsBefore(
                    tenantId, now.minus(settings.notificationRetention()), settings.batchSize());
            int dispatches = repository.redactDispatchesBefore(
                    tenantId, now.minus(settings.dispatchRetention()), settings.batchSize());
            return new Purged(notifications, dispatches);
        });
    }
}
