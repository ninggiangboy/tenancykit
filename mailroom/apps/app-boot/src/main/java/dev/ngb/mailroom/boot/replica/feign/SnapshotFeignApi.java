package dev.ngb.mailroom.boot.replica.feign;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The REST shape of Platform's snapshot API ({@code contracts/openapi/platform.yaml}). It is the only place in
 * Billing that names a snapshot path or query parameter; a null cursor is dropped by Feign, never sent empty.
 */
interface SnapshotFeignApi {

    @GetMapping("/internal/replica/{stream}")
    SnapshotPage page(
            @PathVariable("stream") String stream,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam("limit") int limit);

    /** One page of a stream and the cursor to continue from, or null at the last page. */
    record SnapshotPage(List<Map<String, Object>> items, String next) {

        public SnapshotPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
