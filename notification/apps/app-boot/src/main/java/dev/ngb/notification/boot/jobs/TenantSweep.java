package dev.ngb.notification.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.boot.config.NotificationSettings;
import dev.ngb.notification.platform.api.replica.TenantDirectory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs background work once for every active tenant, each inside that tenant's own scope, because row-level security
 * means a job can only see one tenant at a time.
 *
 * <p>A {@code @Scheduled} method has no request scope, and {@code ScopedTenantContext.current()} throws when none is
 * bound, so a job that called a use case directly would pass its unit test and deliver nothing in production. The
 * sweep reads the tenant list in the Platform scope, then binds each tenant's scope for the work.
 *
 * <p>{@link TenantDirectory#sweptTenantIds()} includes the platform pseudo-tenant, because a Platform user has an inbox
 * and is notified like everyone else: their notification, dispatch and outbox rows live under the Platform tenant id.
 * Skipping it would leave a Platform user's queued email in the outbox forever and never purge their retained rows. The
 * tenant-wide notification types still resolve their audience from tenant users only, so a sweep that visits the
 * Platform tenant does not notify a tenant about Platform state.
 *
 * <p>A failure in one tenant is logged and does not stop the others. Above one, tenants run on virtual threads bounded
 * by one semaphore shared by every job, so a slow tenant cannot delay the ones behind it and the worker never asks the
 * database for more connections than its pool.
 */
@Profile("worker")
@Component
public class TenantSweep {

    private static final Logger LOG = LoggerFactory.getLogger(TenantSweep.class);

    private final TenantContext tenantContext;
    private final TenantDirectory tenants;
    private final int parallelism;
    private final Semaphore permits;

    TenantSweep(TenantContext tenantContext, TenantDirectory tenants, NotificationSettings settings) {
        this.tenantContext = tenantContext;
        this.tenants = tenants;
        this.parallelism = Math.max(1, settings.jobs().tenantParallelism());
        this.permits = new Semaphore(this.parallelism);
    }

    public void forEachActiveTenant(Consumer<TenantId> work) {
        List<UUID> active = tenantContext.callAs(RequestScope.platform(), tenants::sweptTenantIds).stream()
                .map(TenantId::value)
                .toList();
        if (parallelism <= 1) {
            for (UUID tenant : active) {
                runScoped(new TenantId(tenant), tenant.toString(), work);
            }
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = active.stream()
                    .<Callable<Void>>map(tenant -> () -> {
                        runScoped(new TenantId(tenant), tenant.toString(), work);
                        return null;
                    })
                    .toList();
            executor.invokeAll(tasks);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runScoped(TenantId tenantId, String label, Consumer<TenantId> work) {
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            // The scope is bound inside each task: RequestScope lives in a ScopedValue, which does not cross a thread
            // boundary.
            tenantContext.runAs(RequestScope.tenant(tenantId), () -> work.accept(tenantId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.error("background job failed for tenant {}", label, failure);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
}
