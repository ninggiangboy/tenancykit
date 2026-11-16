package dev.ngb.attribution.links.adapter.redirect.suspect;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts clicks per visitor hash and link inside a rolling minute, so a visitor hammering one link is stored as {@link
 * dev.ngb.core.visitor.TrafficClass#SUSPECT} rather than as a person.
 *
 * <p>The counter is in memory and per instance: a distributed flood still classifies as human on some instances, which
 * is the accepted cost of keeping the redirect free of shared state. A suspect click is still answered with its {@code
 * 302} — the visitor is not punished — and is excluded from default reports and from credit.
 */
public class SuspectTracker {

    /** Above this many clicks for one visitor and link inside a minute, the click is suspect. */
    public static final int THRESHOLD = 30;

    private final Cache<String, AtomicInteger> counters;

    public SuspectTracker(Duration window) {
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(1_000_000)
                .build();
    }

    /**
     * Record one click and say whether it is over the threshold.
     *
     * @param visitorHash the daily visitor hash
     * @param linkId the link being followed
     */
    public boolean recordAndCheck(String visitorHash, String linkId) {
        AtomicInteger count = counters.get(visitorHash + "/" + linkId, ignored -> new AtomicInteger());
        return count.incrementAndGet() > THRESHOLD;
    }
}
