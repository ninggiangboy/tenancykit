package dev.ngb.loyalty.rewards.domain.voucher;

import java.util.Objects;
import java.util.UUID;

/** A voucher's own id: the key a Tenant derives its own idempotency keys from, because the code is only a token. */
public record VoucherId(UUID value) {

    public VoucherId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
