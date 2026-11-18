package dev.ngb.loyalty.rewards.application.voucher.query;

import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import java.time.Instant;
import java.util.UUID;

/**
 * A voucher as the read side answers it. {@code expiresAt} is the only expiry there is: a voucher whose instant has
 * passed is still {@code ISSUED} here, and the caller compares the two, so no job has to move a row along.
 */
public record VoucherView(
        UUID voucherId,
        String code,
        UUID rewardId,
        UUID memberId,
        String externalRef,
        VoucherStatus state,
        Instant issuedAt,
        Instant expiresAt,
        Instant usedAt,
        String useRef,
        VoidReason voidReason) {}
