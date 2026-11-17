package dev.ngb.booking.gate.application.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The inner cap on the hold path: a bounded number of in-flight holds per target, per instance.
 *
 * <p>It protects the connection pool, not fairness; the ordered queue is the layer above. The map is size-bounded and
 * drops the least recently used target, because a Tenant can name many offerings and a map that only grows is a leak.
 */
public final class AdmissionControl {

    private static final int MAX_TARGETS = 10_000;

    private final int permitsPerTarget;
    private final Map<String, Semaphore> targets;

    public AdmissionControl(int permitsPerTarget) {
        if (permitsPerTarget < 1) {
            throw new IllegalArgumentException("admission permits per target must be at least 1");
        }
        this.permitsPerTarget = permitsPerTarget;
        this.targets = new LinkedHashMap<>(256, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Semaphore> eldest) {
                return size() > MAX_TARGETS;
            }
        };
    }

    /** @return true when a permit was taken; the caller must release it in a finally block */
    public boolean tryAcquire(String target) {
        Semaphore semaphore;
        synchronized (targets) {
            semaphore = targets.computeIfAbsent(target, key -> new Semaphore(permitsPerTarget));
        }
        return semaphore.tryAcquire();
    }

    public void release(String target) {
        synchronized (targets) {
            Semaphore semaphore = targets.get(target);
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }
}
