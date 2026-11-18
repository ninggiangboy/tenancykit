package dev.ngb.loyalty.rewards.application.voucher.service;

import dev.ngb.loyalty.rewards.application.voucher.exception.VoucherRefusal;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherCode;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import java.time.Instant;

/**
 * The rules a voucher obeys: its code, its use and its expiry.
 *
 * <p>A use is idempotent by the caller's own reference — the same reference on a used voucher replays what the first call
 * did — and a voucher that expired can never be used, because its instant is compared at the moment of the call rather
 * than stored as a state.
 */
public final class VoucherRules {

    /** The longest use reference a caller may send, which is the column's bound and the contract's. */
    public static final int MAX_USE_REF = 200;

    private VoucherRules() {}

    /** @return the code as stored, which is how Crockford's alphabet reads one out loud */
    public static String code(String raw) {
        String value = VoucherCode.canonical(raw);
        if (value.isEmpty() || value.length() > VoucherCode.MAX_LENGTH) {
            throw notFound();
        }
        return value;
    }

    /** @return the caller's reference for the use, trimmed and non-empty */
    public static String useRef(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw VoucherRefusal.invalid("useRef is required and must not be blank.");
        }
        if (value.length() > MAX_USE_REF) {
            throw VoucherRefusal.invalid("useRef has at most " + MAX_USE_REF + " characters.");
        }
        return value;
    }

    /** Refuses a use of a voucher this tenant cannot offer; the caller has already held the row. */
    public static void requireUsable(Voucher voucher, Instant now) {
        if (voucher.expiredAt(now)) {
            throw VoucherRefusal.conflict("VOUCHER_EXPIRED", "The voucher expired at " + voucher.expiresAt() + ".");
        }
        if (voucher.status() != VoucherStatus.ISSUED) {
            throw VoucherRefusal.conflict("VOUCHER_USED", "The voucher was already used with another useRef.");
        }
    }

    /**
     * The answer to a use: the voucher is usable now, so the caller writes; it was used by this very reference, so the
     * caller replays; anything else is a refusal.
     *
     * @return true when this call must write the use, false when the same reference already did
     */
    public static boolean shouldWrite(Voucher voucher, String useRef, Instant now) {
        if (voucher.replayedBy(useRef)) {
            return false;
        }
        requireUsable(voucher, now);
        return true;
    }

    /** An unknown code and another tenant's code answer the same constant, so a guess learns nothing. */
    public static VoucherRefusal notFound() {
        return VoucherRefusal.notFound("VOUCHER_NOT_FOUND", "No voucher with that code exists in this tenant.");
    }
}
