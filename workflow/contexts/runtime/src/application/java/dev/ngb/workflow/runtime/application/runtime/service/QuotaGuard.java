package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.application.runtime.exception.QuotaExceededException;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.QuotaRepository;
import java.util.Map;

/**
 * The one place a start is refused for a reason that is not the workflow's own. The count and the insert are not one
 * atomic decision, so a burst can overshoot by the number of concurrent requests; the cap bounds a Tenant rather than
 * promising an exact ceiling, which is what the risk register asks for.
 */
public class QuotaGuard {

    private final UnitOfWork unitOfWork;
    private final QuotaRepository quotas;
    private final InstanceRepository instances;
    private final QuotaRepository.Limits defaults;

    public QuotaGuard(
            UnitOfWork unitOfWork,
            QuotaRepository quotas,
            InstanceRepository instances,
            QuotaRepository.Limits defaults) {
        this.unitOfWork = unitOfWork;
        this.quotas = quotas;
        this.instances = instances;
        this.defaults = defaults;
    }

    /** Refuses a start when the Tenant already runs its allowed number of instances. */
    public void requireStart(TenantId tenant) {
        QuotaRepository.Limits limits = unitOfWork.transactional(() -> quotas.forTenant(tenant).orElse(defaults));
        long running = unitOfWork.transactional(() -> instances.liveCount(tenant));
        if (running >= limits.concurrentInstances()) {
            throw new QuotaExceededException("concurrent_instances: this tenant already runs "
                    + running + " of " + limits.concurrentInstances());
        }
    }

    /** The limits a caller may show in a problem body without exposing another Tenant's row. */
    public Map<String, Object> describe(TenantId tenant) {
        QuotaRepository.Limits limits = unitOfWork.transactional(() -> quotas.forTenant(tenant).orElse(defaults));
        return Map.of("concurrentInstances", limits.concurrentInstances());
    }

    public QuotaRepository.Limits defaults() {
        return defaults;
    }
}
