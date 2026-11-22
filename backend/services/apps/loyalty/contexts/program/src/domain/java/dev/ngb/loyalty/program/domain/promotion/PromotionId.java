package dev.ngb.loyalty.program.domain.promotion;

import java.util.Objects;
import java.util.UUID;

/** A promotion's own id, stable across updates so an earn record can name the rules it used. */
public record PromotionId(UUID value) {

    public PromotionId {
        Objects.requireNonNull(value, "value");
    }

    public static PromotionId of(String text) {
        return new PromotionId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
