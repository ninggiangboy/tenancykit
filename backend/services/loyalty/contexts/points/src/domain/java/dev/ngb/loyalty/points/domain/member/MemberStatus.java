package dev.ngb.loyalty.points.domain.member;

/** Where a member stands. An {@link #ERASED} member keeps its ledger but has released its reference. */
public enum MemberStatus {
    ACTIVE,
    ERASED
}
