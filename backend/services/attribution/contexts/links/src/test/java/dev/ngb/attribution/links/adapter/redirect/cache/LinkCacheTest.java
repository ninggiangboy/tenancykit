package dev.ngb.attribution.links.adapter.redirect.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.LinkStatus;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The cache is what keeps a redirect off the database, so its two promises are pinned here: a viral slug loads once, and
 * an unknown slug is remembered rather than looked up on every probe.
 */
class LinkCacheTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    void aConcurrentColdStartIssuesOneQuery() throws Exception {
        CountingLinkRepository links = new CountingLinkRepository();
        String tenant = UUID.randomUUID().toString();
        links.store.add(link(tenant, "spring-sale"));
        LinkCache cache = new LinkCache(links, immediateUnitOfWork(), Duration.ofSeconds(30), 100);
        int callers = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (cache.find(tenant, "spring-sale").isPresent()) {
                        loads.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(loads.get()).isEqualTo(callers);
        assertThat(links.bySlugCalls.get()).as("a viral link's cold start must load once").isEqualTo(1);
    }

    @Test
    void anUnknownSlugIsRememberedSoProbesDoNotReachTheDatabase() {
        CountingLinkRepository links = new CountingLinkRepository();
        LinkCache cache = new LinkCache(links, immediateUnitOfWork(), Duration.ofSeconds(30), 100);

        for (int index = 0; index < 20; index++) {
            assertThat(cache.find("tenant", "random-slug")).isEmpty();
        }

        assertThat(links.bySlugCalls.get()).isEqualTo(1);
    }

    @Test
    void aSlugIsScopedToItsTenant() {
        CountingLinkRepository links = new CountingLinkRepository();
        links.store.add(link("tenant-a", "spring-sale"));
        LinkCache cache = new LinkCache(links, immediateUnitOfWork(), Duration.ofSeconds(30), 100);

        // The production repository reads under row-level security, so the tenant in scope is the one a load sees. The
        // stand-in mirrors that by reading only the rows whose tenant it was told is current.
        links.currentTenant = "tenant-a";
        assertThat(cache.find("tenant-a", "spring-sale")).isPresent();
        links.currentTenant = "tenant-b";
        assertThat(cache.find("tenant-b", "spring-sale")).isEmpty();
    }

    @Test
    void aWriteOnThisInstanceCanEvictTheEntryImmediately() {
        CountingLinkRepository links = new CountingLinkRepository();
        links.store.add(link("tenant", "spring-sale"));
        LinkCache cache = new LinkCache(links, immediateUnitOfWork(), Duration.ofHours(1), 100);
        assertThat(cache.find("tenant", "spring-sale")).isPresent();
        links.store.clear();

        cache.evict("tenant", "spring-sale");

        assertThat(cache.find("tenant", "spring-sale")).isEmpty();
    }

    private static Link link(String tenant, String slug) {
        return new Link(
                UUID.randomUUID(), new TenantId(tenantId(tenant)), null,
                Slug.of(slug), LinkKind.STANDARD, null, LinkStatus.ACTIVE, null, null, null, Map.of(),
                List.of(new Destination(
                        UUID.randomUUID(), new DestinationUrl("https://example.com/a"), 0, 1, DeviceMatch.any(), true)),
                1, NOW, NOW);
    }

    /** A stable tenant id per label, so a link and a lookup that name the same label agree. */
    private static UUID tenantId(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UnitOfWork immediateUnitOfWork() {
        return new UnitOfWork() {
            @Override
            public <T> T transactional(Supplier<T> work) {
                return work.get();
            }
        };
    }

    /** Counts how many times a slug is actually read, which is what the single-flight promise is about. */
    private static final class CountingLinkRepository implements LinkRepository {

        private final List<Link> store = new ArrayList<>();
        private final AtomicInteger bySlugCalls = new AtomicInteger();
        private volatile String currentTenant;

        @Override
        public void insert(Link link) {
            store.add(link);
        }

        @Override
        public Optional<Link> findById(UUID linkId) {
            return store.stream().filter(link -> link.id().equals(linkId)).findFirst();
        }

        @Override
        public synchronized Optional<Link> findBySlug(String slug) {
            bySlugCalls.incrementAndGet();
            return store.stream()
                    .filter(link -> link.slug().value().equals(slug))
                    // The production repository reads under row-level security, so this stand-in reads only the tenant
                    // the lookup is acting for; the cache key and the query agree when the tenant is passed through.
                    .filter(link -> currentTenant == null || link.tenantId().value().equals(tenantId(currentTenant)))
                    .findFirst();
        }

        @Override
        public boolean update(Link link, long expectedVersion) {
            return true;
        }

        @Override
        public boolean slugTaken(String slug) {
            return store.stream().anyMatch(link -> link.slug().value().equals(slug));
        }

        @Override
        public boolean incrementIfBelow(UUID linkId, long cap) {
            return true;
        }

        @Override
        public long clicksCounted(UUID linkId) {
            return 0;
        }
    }
}
