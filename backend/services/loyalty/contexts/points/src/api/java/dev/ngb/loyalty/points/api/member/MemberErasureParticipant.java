package dev.ngb.loyalty.points.api.member;

import java.util.UUID;

/**
 * The points context's contract for a context that holds something about a member: when the member's personal data is
 * erased, the caller's part of it is erased with it, in the member's erasure transaction.
 *
 * <p>It exists so an erasure reaches every table that can hold a person without the points context reaching into another
 * context's data: the context that owns the rows is the one that clears them, and the wiring layer hands it to the
 * erasure. A context that keeps nothing about a member implements nothing.
 */
public interface MemberErasureParticipant {

    /**
     * Clears the personal data this context holds about the member, in the caller's transaction. A row that only marks that
     * something happened — an order, a ledger entry, a redemption — stays, because it is keyed by the opaque member id and
     * names nobody.
     *
     * @param memberId loyalty's own id for the member whose data is being erased
     * @return how many of this context's own records the erasure stopped being usable
     */
    int erase(UUID memberId);

    /**
     * How many of this context's records that member's erasure already stopped being usable, which is what a replayed
     * erasure call answers. It reads what {@link #erase(UUID)} wrote and changes nothing.
     */
    int erasedRecords(UUID memberId);
}
