package dev.ngb.booking.gate.adapter.queue.persistence;

import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.booking.gate.application.queue.port.WatermarkCache;
import dev.ngb.core.runtime.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One watermark per target, refreshed at most once a second. A poll therefore costs a map lookup in the common case,
 * and the answer can lag the database by a second — the queue promises order, not an exact rank.
 */
public class CachedQueueWatermarks implements WatermarkCache {

    private static final Duration TTL = Duration.ofSeconds(1);

    private final QueueTicketRepository tickets;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public CachedQueueWatermarks(QueueTicketRepository tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    @Override
    public long of(String targetId) {
        long now = clock.now().toEpochMilli();
        return entries.compute(targetId, (key, current) -> {
            if (current == null || now - current.readAtMillis >= TTL.toMillis()) {
                return new Entry(tickets.watermark(targetId), now);
            }
            return current;
        }).watermark;
    }

    private static final class Entry {
        private final long watermark;
        private final long readAtMillis;

        private Entry(long watermark, long readAtMillis) {
            this.watermark = watermark;
            this.readAtMillis = readAtMillis;
        }
    }
}
