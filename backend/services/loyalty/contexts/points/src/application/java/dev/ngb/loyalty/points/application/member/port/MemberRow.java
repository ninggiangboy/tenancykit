package dev.ngb.loyalty.points.application.member.port;

import dev.ngb.loyalty.points.domain.member.MemberStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member row as the writing side reads and writes it: the identity, the reference the Tenant knows it by, the tier it
 * holds and the debt the next earn repays.
 */
public record MemberRow(
        UUID id,
        String externalRef,
        String displayName,
        String tierCode,
        long debt,
        MemberStatus status,
        Instant enrolledAt,
        long version) {

    public MemberRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(enrolledAt, "enrolledAt");
        if (debt < 0) {
            throw new IllegalArgumentException("debt must not be negative");
        }
    }

    /** A member enrolled just now, holding no tier until an earn or the tier evaluation decides one. */
    public static MemberRow enrolled(UUID id, String externalRef, String displayName, Instant at) {
        return new MemberRow(id, externalRef, displayName, null, 0L, MemberStatus.ACTIVE, at, 1L);
    }

    /** The same member after a flow changed its tier, its debt, or both. */
    public MemberRow with(String newTierCode, long newDebt) {
        return new MemberRow(id, externalRef, displayName, newTierCode, newDebt, status, enrolledAt, version);
    }
}
