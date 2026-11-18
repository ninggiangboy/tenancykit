package dev.ngb.platform.boot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.access.application.auth.port.ConsoleClientPort;
import dev.ngb.platform.tenancy.application.tenant.query.TenantDomainView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantQueries;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The reconciler makes the client's hosts exactly the fixed hosts, the platform host and every active tenant's host.
 * The published port is the client's to apply, so a host is handed over exactly as it is recorded.
 */
class ConsoleRedirectSyncJobTest {

    private static final UnitOfWork IMMEDIATE = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final RecordingConsoleClient consoleClient = new RecordingConsoleClient();
    private final List<String> activeHosts = new ArrayList<>(List.of("acme.localhost", "beta.localhost"));
    private final TenantQueries tenants = new TenantQueries(IMMEDIATE, new StubTenants());

    @Test
    void theDesiredSetIsTheFixedHostsThePlatformHostAndEveryActiveTenant() {
        Set<String> desired = job().desiredHosts();

        assertThat(desired).containsExactlyInAnyOrder(
                "localhost", "platform.localhost", "localhost:5173", "platform.localhost:5173",
                "acme.localhost", "beta.localhost");
    }

    @Test
    void theSyncIsTheSameOnEveryRunSoItIsSafeToRepeat() {
        ConsoleRedirectSyncJob job = job();

        job.reconcile();
        job.reconcile();

        assertThat(consoleClient.synced).hasSize(2);
        assertThat(consoleClient.synced.get(0)).isEqualTo(consoleClient.synced.get(1));
        assertThat(consoleClient.synced.get(1)).contains("acme.localhost", "platform.localhost");
    }

    @Test
    void aLeaseHeldElsewhereSkipsTheRun() {
        ConsoleRedirectSyncJob job = new ConsoleRedirectSyncJob(
                new dev.ngb.core.spring.tenancy.ScopedTenantContext(), tenants, consoleClient,
                (name, duration) -> false, List.of("localhost"), "platform.localhost");

        job.reconcile();

        assertThat(consoleClient.synced).isEmpty();
    }

    private ConsoleRedirectSyncJob job() {
        return new ConsoleRedirectSyncJob(
                new dev.ngb.core.spring.tenancy.ScopedTenantContext(),
                tenants,
                consoleClient,
                (name, duration) -> true,
                List.of("localhost", "platform.localhost", "localhost:5173", "platform.localhost:5173"),
                "platform.localhost");
    }

    private record RecordingConsoleClient(List<Set<String>> synced) implements ConsoleClientPort {

        RecordingConsoleClient() {
            this(new ArrayList<>());
        }

        @Override
        public void registerHost(String host) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeHost(String host) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void syncHosts(Set<String> hosts) {
            synced.add(new LinkedHashSet<>(hosts));
        }
    }

    private class StubTenants implements TenantReadRepository {

        @Override
        public Page<TenantView> list(ListQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TenantView> all() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TenantView> page(UUID after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TenantView> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TenantDomainView> pageDomains(UUID after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TenantDomainView> findDomainByHostname(String hostname) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TenantDomainView> listDomains(UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> activeConsoleHosts() {
            return activeHosts;
        }
    }
}
