package dev.ngb.attribution.tracking.application.ingest.command;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.attribution.tracking.application.ingest.port.ClickStore;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one broker batch into stored clicks.
 *
 * <p>Row-level security binds a transaction to one tenant, so the batch is grouped by tenant and each group is inserted
 * in its own tenant-scoped transaction. A group that fails is retried, then bisected until the one row that cannot be
 * stored is isolated; that row is returned as dead so the caller can dead-letter it, and the rest of the batch commits.
 * A redelivered batch writes nothing twice, because the store's insert is idempotent on the event id.
 */
public class IngestClicksUseCase {

    /**
     * What one batch produced.
     *
     * @param stored rows actually written, fewer than the batch when it was a redelivery
     * @param duplicates rows already present, so a redelivery rather than a loss
     * @param dead rows that could not be stored at all, which the caller dead-letters
     */
    public record Result(int stored, int duplicates, List<ClickEvent> dead) {
        public Result {
            dead = List.copyOf(dead);
        }

        public int deadCount() {
            return dead.size();
        }
    }

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ClickStore store;
    private final int attempts;

    /**
     * @param attempts how many times a failing group is retried before it is bisected, at least 1
     */
    public IngestClicksUseCase(UnitOfWork unitOfWork, TenantContext tenantContext, ClickStore store, int attempts) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.store = store;
        this.attempts = Math.max(1, attempts);
    }

    public Result execute(List<ClickEvent> batch) {
        if (batch == null || batch.isEmpty()) {
            return new Result(0, 0, List.of());
        }
        int stored = 0;
        List<ClickEvent> dead = new ArrayList<>();
        for (Map.Entry<TenantId, List<ClickEvent>> group : byTenant(batch).entrySet()) {
            Outcome outcome = insertGroup(group.getKey(), group.getValue());
            stored += outcome.stored();
            dead.addAll(outcome.dead());
        }
        // A row neither stored nor dead was already present: a redelivery, not a loss.
        return new Result(stored, batch.size() - stored - dead.size(), dead);
    }

    private Map<TenantId, List<ClickEvent>> byTenant(List<ClickEvent> batch) {
        Map<TenantId, List<ClickEvent>> groups = new LinkedHashMap<>();
        for (ClickEvent event : batch) {
            groups.computeIfAbsent(event.tenantId(), ignored -> new ArrayList<>()).add(event);
        }
        return groups;
    }

    private Outcome insertGroup(TenantId tenantId, List<ClickEvent> events) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return new Outcome(insertAsTenant(tenantId, events), List.of());
            } catch (RuntimeException failure) {
                backoff(attempt);
            }
        }
        // The group keeps failing: bisect to isolate the row or rows that cannot be stored rather than losing the batch.
        if (events.size() == 1) {
            return new Outcome(0, List.of(events.getFirst()));
        }
        int middle = events.size() / 2;
        Outcome left = insertGroup(tenantId, events.subList(0, middle));
        Outcome right = insertGroup(tenantId, events.subList(middle, events.size()));
        if (left.stored() == 0 && right.stored() == 0 && left.dead().isEmpty() && right.dead().isEmpty()) {
            // Both halves failed wholesale with nothing isolated: this tenant's store is unusable right now, so the
            // whole group is dead rather than retried forever.
            return new Outcome(0, List.copyOf(events));
        }
        List<ClickEvent> dead = new ArrayList<>(left.dead());
        dead.addAll(right.dead());
        return new Outcome(left.stored() + right.stored(), dead);
    }

    private int insertAsTenant(TenantId tenantId, List<ClickEvent> events) {
        // The scope is bound around the transaction, which is what the row-level-security setting is installed from.
        Integer stored = tenantContext.callAs(
                RequestScope.tenant(tenantId), () -> unitOfWork.transactional(() -> store.insertAll(events)));
        return stored == null ? 0 : stored;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(200L, 20L << Math.min(attempt, 3)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Outcome(int stored, List<ClickEvent> dead) {}
}
