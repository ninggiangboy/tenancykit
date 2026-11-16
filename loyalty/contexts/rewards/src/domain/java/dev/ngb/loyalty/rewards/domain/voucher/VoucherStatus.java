package dev.ngb.loyalty.rewards.domain.voucher;

/**
 * The life of a voucher: issued by a redemption, used at most once, or voided when the redemption is reversed or the
 * member is erased. Expiry is not a state here: it is read from {@code expiresAt} at the moment of the read, so no job
 * has to move a voucher along.
 */
public enum VoucherStatus {
    ISSUED,
    USED,
    VOIDED
}
