package dev.ngb.core.replica;

import java.util.List;
import java.util.Map;

/**
 * Fetches each replica stream from Platform's snapshot API. It first exchanges the configured service client for a
 * token whose audience is {@code platform}, then pages a stream until {@code next} is null.
 */
public class SnapshotClient {

    /** What the snapshot API answers: one page of items and the cursor to continue from, or null at the end. */
    public record Page(List<Map<String, Object>> items, String next) {

        public Page {
            items = List.copyOf(items);
        }
    }

    private final SnapshotTransport transport;

    public SnapshotClient(SnapshotTransport transport) {
        this.transport = transport;
    }

    /** Pages one stream from the beginning to its last page. */
    public List<Map<String, Object>> fetchAll(String stream, int pageLimit) {
        String token = transport.serviceToken();
        String cursor = null;
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        while (true) {
            Page page = transport.page(stream, cursor, pageLimit, token);
            all.addAll(page.items());
            if (page.next() == null) {
                return List.copyOf(all);
            }
            cursor = page.next();
        }
    }

    /** The HTTP calls the snapshot client needs; the boot layer supplies the Spring Web implementation. */
    public interface SnapshotTransport {

        /** A service token whose audience is {@code platform}. */
        String serviceToken();

        Page page(String stream, String after, int limit, String bearerToken);
    }
}
