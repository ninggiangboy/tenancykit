package dev.ngb.platform.boot.jobs;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.access.application.auth.port.ConsoleClientPort;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes the console client's redirect URIs equal the hosts that exist: the platform host, the fixed development hosts
 * and every active Tenant's own host. It runs at start, so a boot repairs drift the add-domain hook could not apply, and
 * then on a delay. The lease keeps several Platform instances from doing it at once.
 */
@Component
@ConditionalOnProperty(prefix = "platform.keycloak", name = "console-sync-enabled", havingValue = "true", matchIfMissing = true)
public class ConsoleRedirectSyncJob {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleRedirectSyncJob.class);

    private final TenantContext tenantContext;
    private final TenantQueries tenants;
    private final ConsoleClientPort consoleClient;
    private final JobLease lease;
    private final List<String> fixedHosts;
    private final String platformHostname;

    ConsoleRedirectSyncJob(
            TenantContext tenantContext,
            TenantQueries tenants,
            ConsoleClientPort consoleClient,
            JobLease lease,
            @Value("${platform.keycloak.console-fixed-hosts:localhost,platform.localhost,localhost:5173,platform.localhost:5173}")
                    List<String> fixedHosts,
            @Value("${platform.tenancy.platform-hostname:platform.localhost}") String platformHostname) {
        this.tenantContext = tenantContext;
        this.tenants = tenants;
        this.consoleClient = consoleClient;
        this.lease = lease;
        this.fixedHosts = List.copyOf(fixedHosts);
        this.platformHostname = platformHostname;
    }

    @Scheduled(
            fixedDelayString = "${platform.keycloak.console-sync-delay:PT5M}",
            initialDelayString = "${platform.keycloak.console-sync-initial-delay:PT15S}")
    public void reconcile() {
        // The whole job is one Keycloak read and write, so a short lease bounds the wait after a restart while still
        // keeping two instances from writing the client at once.
        if (!lease.tryAcquire("keycloak-console-sync", Duration.ofSeconds(30))) {
            return;
        }
        Set<String> desired = desiredHosts();
        consoleClient.syncHosts(desired);
        LOG.debug("the console client now accepts {} hosts", desired.size());
    }

    /**
     * The complete set, so the call is idempotent: fixed hosts, the platform host and every active Tenant's host. The
     * port the console is published on is the client's to apply, so the same host reaches the same redirect target
     * whether it came from here or from a domain add.
     */
    Set<String> desiredHosts() {
        Set<String> hosts = new LinkedHashSet<>(fixedHosts);
        hosts.add(platformHostname);
        tenantContext
                .callAs(RequestScope.platform(), tenants::activeConsoleHosts)
                .forEach(hosts::add);
        return hosts;
    }
}
