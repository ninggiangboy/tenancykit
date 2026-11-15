package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.dispatch.application.notification.port.NotificationReadStateRepository;
import java.util.UUID;

/** Marks one of the caller's notifications read, or all of them. Another user's id is a 404, not a 403. */
public class MarkNotificationReadUseCase {

    private final UnitOfWork unitOfWork;
    private final NotificationReadStateRepository repository;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;
    private final Clock clock;

    public MarkNotificationReadUseCase(
            UnitOfWork unitOfWork,
            NotificationReadStateRepository repository,
            TenantContext tenantContext,
            CurrentActor currentActor,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    public void markRead(UUID notificationId) {
        unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            UUID userId = currentActor.get().id();
            if (!repository.exists(tenantId, userId, notificationId)) {
                throw new NotFoundException("This notification does not exist.");
            }
            repository.markRead(tenantId, userId, notificationId, clock.now());
            return null;
        });
    }

    public int markAllRead() {
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            UUID userId = currentActor.get().id();
            return repository.markAllRead(tenantId, userId, clock.now());
        });
    }
}
