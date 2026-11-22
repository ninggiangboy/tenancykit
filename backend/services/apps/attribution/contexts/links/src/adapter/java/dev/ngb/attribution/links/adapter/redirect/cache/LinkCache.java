package dev.ngb.attribution.links.adapter.redirect.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.util.Optional;

/**
 * An in-process cache of resolved links, so a redirect never waits on the database.
 *
 * <p>The key is the tenant and the slug, because a slug is unique only within a tenant and the host is what decides the
 * tenant. A miss loads the link once and everyone waiting on the same slug receives that one load — Caffeine's loading
 * cache is single-flight — so a viral link's cold start issues one query, not one per request.
 *
 * <p>An unknown slug is cached as an empty entry, so a flood of random slugs cannot reach the database more than once
 * per slug per TTL. The cost is that a newly created link can answer {@code 404} for at most the TTL on an instance that
 * already looked it up, and a paused or archived link can keep redirecting for the same time; both are documented and
 * bounded by {@code attribution.redirect.cache-ttl}.
 */
public class LinkCache {

    /** What a key resolves to: a link, or a negative entry meaning no such slug in this tenant. */
    public sealed interface Resolution {
        record Found(Link link) implements Resolution {}

        record Missing() implements Resolution {}
    }

    private final LoadingCache<Key, Resolution> cache;

    /**
     * @param links the write-side repository, which reads a link with its destinations in one load
     * @param ttl how long a resolved entry, positive or negative, lives before the next read reloads it
     * @param maxSize how many slugs one instance keeps, so a hostile slug cannot grow the heap
     */
    public LinkCache(LinkRepository links, UnitOfWork unitOfWork, Duration ttl, int maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build(key -> unitOfWork.transactional(
                        () -> links.findBySlug(key.slug()).<Resolution>map(Resolution.Found::new).orElseGet(Resolution.Missing::new)));
    }

    /** The link a slug names in a tenant, or empty when it does not exist. */
    public Optional<Link> find(String tenantId, String slug) {
        Resolution resolution = cache.get(new Key(tenantId, slug));
        return resolution instanceof Resolution.Found found ? Optional.of(found.link()) : Optional.empty();
    }

    /** Forget one slug immediately, so a write on this instance reaches the next redirect without waiting for the TTL. */
    public void evict(String tenantId, String slug) {
        cache.invalidate(new Key(tenantId, slug));
    }

    private record Key(String tenantId, String slug) {}
}
