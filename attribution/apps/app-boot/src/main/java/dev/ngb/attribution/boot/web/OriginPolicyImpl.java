package dev.ngb.attribution.boot.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.ngb.attribution.links.application.settings.query.SettingsReadRepository;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Whether a request's {@code Origin} may post to this tenant's collection endpoint.
 *
 * <p>The check is against the tenant's own {@code allowed_origins}, so a page on a domain the Tenant never named cannot
 * post events with another site's click ids. A request with no {@code Origin} is refused: a same-origin form post and a
 * `sendBeacon` both carry one, and refusing an absent value keeps the rule one branch rather than a special case.
 *
 * <p>The list is read from the settings and cached for a short window, because it is read on every page view and a
 * database round trip there would defeat the endpoint's own rate limit.
 */
public class OriginPolicyImpl implements CollectController.OriginPolicy {

    private final UnitOfWork unitOfWork;
    private final SettingsReadRepository settings;
    private final Cache<String, List<String>> cache;
    private final String defaultClickParam;
    private final int defaultWindowDays;

    /**
     * @param ttl how long a tenant's allowed origins are remembered before the next read reloads them
     */
    public OriginPolicyImpl(
            UnitOfWork unitOfWork,
            SettingsReadRepository settings,
            Duration ttl,
            String defaultClickParam,
            int defaultWindowDays) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
        this.defaultClickParam = defaultClickParam;
        this.defaultWindowDays = defaultWindowDays;
    }

    @Override
    public boolean allowed(String origin, String host) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String normalized = normalize(origin);
        if (normalized == null) {
            return false;
        }
        List<String> allowed = cache.get("origins", ignored -> unitOfWork.transactional(
                () -> settings.load(defaultClickParam, defaultWindowDays).allowedOrigins()));
        return allowed.stream().map(OriginPolicyImpl::normalize).anyMatch(normalized::equals);
    }

    /** Lower-cased {@code scheme://host[:port]}, or null when the value is not an origin. */
    private static String normalize(String origin) {
        try {
            java.net.URI uri = new java.net.URI(origin.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + port;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
