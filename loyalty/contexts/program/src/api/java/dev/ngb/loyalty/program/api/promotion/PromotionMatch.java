package dev.ngb.loyalty.program.api.promotion;

import java.time.Instant;

/**
 * One thing a line is, as the promotion conditions see it: the store it was sold in, its category and sku, the member's
 * tier and when it happened. Any field may be null, and a condition kind that restricts a field then does not match.
 */
public record PromotionMatch(String storeCode, String category, String sku, String tierCode, Instant at) {

    public PromotionMatch {
        java.util.Objects.requireNonNull(at, "at");
    }
}
