package dev.ngb.attribution.boot.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The per-click-id daily budget for page views.
 *
 * <p>One click id grows a chain of page views; a page view that names a click over the day's budget is a client looping
 * or a replay rather than a visit, so the batch is refused. The counter is in memory and per instance, keyed by the
 * click token and the UTC day, and it resets when the day changes because the key carries the day.
 */
public class ClickDailyLimiterImpl implements CollectController.ClickDailyLimiter {

    private static final int PER_CLICK_PER_DAY = 500;

    private final Cache<String, AtomicInteger> counters;

    public ClickDailyLimiterImpl(Duration idleExpiry) {
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(idleExpiry)
                .maximumSize(1_000_000)
                .build();
    }

    @Override
    public boolean allow(List<String> clickIds) {
        if (clickIds == null || clickIds.isEmpty()) {
            return true;
        }
        String day = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        // Refuse the whole batch when any one click id is over its budget, so the endpoint's answer is one branch and a
        // partial write cannot inflate a click's page views across a retried batch.
        for (String clickId : clickIds) {
            if (clickId == null || clickId.isBlank()) {
                continue;
            }
            AtomicInteger count = counters.get(day + "/" + clickId, ignored -> new AtomicInteger());
            if (count.incrementAndGet() > PER_CLICK_PER_DAY) {
                return false;
            }
        }
        return true;
    }

    /** The window is a day plus a margin, so a counter never lingers into the next day's key. */
    public static Duration retention() {
        return Duration.ofHours(25).plus(ChronoUnit.MINUTES.getDuration().multipliedBy(1));
    }
}
