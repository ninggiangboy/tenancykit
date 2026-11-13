package dev.ngb.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Paging a stream: every page reaches the consumer, and a cursor that never advances fails instead of looping. */
class SnapshotClientTest {

    @Test
    void handsEveryPageToTheConsumerOneAtATime() {
        RecordingTransport transport = new RecordingTransport(List.of(
                new SnapshotClient.Page(List.of(item("a")), "b"),
                new SnapshotClient.Page(List.of(item("b")), "c"),
                new SnapshotClient.Page(List.of(item("c")), null)));
        List<SnapshotClient.Page> seen = new ArrayList<>();

        int pages = new SnapshotClient(transport).fetchPages("tenants", 2, seen::add);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(3);
        assertThat(transport.cursors).containsExactly(null, "b", "c");
    }

    @Test
    void aCursorThatNeverAdvancesFails() {
        RecordingTransport transport = new RecordingTransport(List.of(
                new SnapshotClient.Page(List.of(item("a")), "b"),
                new SnapshotClient.Page(List.of(item("b")), "b")));

        assertThatThrownBy(() -> new SnapshotClient(transport).fetchPages("tenants", 2, page -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never advances");
    }

    @Test
    void aStreamThatNeverEndsFailsAtThePageLimit() {
        SnapshotClient client = new SnapshotClient(new EndlessTransport());

        assertThatThrownBy(() -> client.fetchPages("tenants", 1, page -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(SnapshotClient.MAX_PAGES));
    }

    private static Map<String, Object> item(String id) {
        return Map.of("id", id);
    }

    /** Answers a fixed list of pages and records the cursor each request carried. */
    private static final class RecordingTransport implements SnapshotClient.SnapshotTransport {

        private final List<SnapshotClient.Page> pages;
        private final List<String> cursors = new ArrayList<>();
        private int served;

        RecordingTransport(List<SnapshotClient.Page> pages) {
            this.pages = pages;
        }

        @Override
        public String serviceToken() {
            return "test-token";
        }

        @Override
        public SnapshotClient.Page page(String stream, String after, int limit, String bearerToken) {
            cursors.add(after);
            return pages.get(served++);
        }
    }

    /** Answers a fresh cursor for ever, so only the client's page limit stops it. */
    private static final class EndlessTransport implements SnapshotClient.SnapshotTransport {

        private int served;

        @Override
        public String serviceToken() {
            return "test-token";
        }

        @Override
        public SnapshotClient.Page page(String stream, String after, int limit, String bearerToken) {
            served++;
            return new SnapshotClient.Page(List.of(item("item-" + served)), "cursor-" + served);
        }
    }
}
