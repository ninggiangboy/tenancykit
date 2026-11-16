package dev.ngb.loyalty.points.domain.ledger;

/** What a ledger entry does to a member's points. An entry is never changed; a correction is another entry. */
public enum LedgerKind {
    /** Points granted for an order; the part not spent repaying debt becomes a lot. */
    EARN,
    /** Points taken back on a return, consumed from lots and then recorded as debt. */
    CLAWBACK,
    /** Points spent on a redemption. */
    REDEEM,
    /** Points restored when a redemption is reversed. */
    REDEEM_REVERSAL,
    /** Points written off because their lot expired. */
    EXPIRE,
    /** Points released because the member was erased. */
    ERASE;

    /** The name the API document uses for this kind, which is this one but for a reversal. */
    public String wireName() {
        return this == REDEEM_REVERSAL ? "REVERSE" : name();
    }

    /** Whether this entry adds to what the member has, which is what the balance sums. */
    public boolean grants() {
        return this == EARN || this == REDEEM_REVERSAL;
    }
}
