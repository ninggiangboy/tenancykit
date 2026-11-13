package dev.ngb.core.sync;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fetches each replica stream from Platform's snapshot API. It first exchanges the configured service client for a
 * token whose audience is {@code platform}, then pages a stream until {@code next} is null.
 *
 * <p>A page is handed to a consumer as it arrives, so a rebuild holds one page in memory however long the stream is.
 * A server that repeats a cursor, or that never stops answering pages, is a contract violation rather than a rebuild:
 * the client fails instead of looping or paging for ever.
 */
public class SnapshotClient {

    /** What the snapshot API answers: one page of items and the cursor to continue from, or null at the end. */
    public record Page(List<Map<String, Object>> items, String next) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /** The most pages one stream may take before the client gives up, so a stream that never ends cannot loop. */
    public static final int MAX_PAGES = 10_000;

    private final SnapshotTransport transport;

    public SnapshotClient(SnapshotTransport transport) {
        this.transport = transport;
    }

    /**
     * Pages one stream from the beginning to its last page, handing each page to {@code consumer}.
     *
     * @return how many pages the stream took
     * @throws IllegalStateException when the cursor does not advance, or the stream answers more than
     *     {@link #MAX_PAGES} pages
     */
    public int fetchPages(String stream, int pageLimit, Consumer<Page> consumer) {
        String token = transport.serviceToken();
        String cursor = null;
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Page answer = transport.page(stream, cursor, pageLimit, token);
            consumer.accept(answer);
            if (answer.next() == null) {
                return page;
            }
            if (answer.next().equals(cursor) || !seen.add(answer.next())) {
                throw new IllegalStateException(
                        "the snapshot cursor for stream '" + stream + "' answered " + answer.next()
                                + " again, so the stream never advances");
            }
            cursor = answer.next();
        }
        throw new IllegalStateException(
                "the snapshot stream '" + stream + "' answered more than " + MAX_PAGES + " pages");
    }

    /** The HTTP calls the snapshot client needs; the boot layer supplies the Spring Web implementation. */
    public interface SnapshotTransport {

        /** A service token whose audience is {@code platform}. */
        String serviceToken();

        Page page(String stream, String after, int limit, String bearerToken);
    }
}
