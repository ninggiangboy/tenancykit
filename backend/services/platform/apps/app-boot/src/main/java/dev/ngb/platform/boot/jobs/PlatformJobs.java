package dev.ngb.platform.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes every tenant's committed outbox events to the broker, once per tenant because row-level security means
 * a transaction sees one tenant at a time. A failure in one tenant is logged and does not stop the others.
 */
@Component
public class PlatformJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformJobs.class);

    private final TenantContext tenantContext;
    private final TenantQueries tenants;
    private final BrokerRelay relay;

    PlatformJobs(TenantContext tenantContext, TenantQueries tenants, BrokerRelay relay) {
        this.tenantContext = tenantContext;
        this.tenants = tenants;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-delay:PT1S}")
    public void relay() {
        List<TenantView> active = tenantContext.callAs(RequestScope.platform(), tenants::all);
        for (TenantView tenant : active) {
            try {
                tenantContext.runAs(RequestScope.tenant(new TenantId(tenant.id())), relay::relayDue);
            } catch (RuntimeException failure) {
                LOG.error("broker relay failed for tenant {}", tenant.code(), failure);
            }
        }
    }
}
