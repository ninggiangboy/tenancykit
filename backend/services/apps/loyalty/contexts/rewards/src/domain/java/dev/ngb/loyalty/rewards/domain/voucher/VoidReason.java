package dev.ngb.loyalty.rewards.domain.voucher;

/**
 * Why a voucher stopped being usable without being used: the redemption that issued it was reversed, or the member was
 * erased and its unused vouchers went with it.
 */
public enum VoidReason {
    REVERSAL,
    ERASURE
}
