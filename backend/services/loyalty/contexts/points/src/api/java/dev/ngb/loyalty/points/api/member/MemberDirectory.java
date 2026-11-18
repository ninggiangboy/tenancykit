package dev.ngb.loyalty.points.api.member;

import java.util.Optional;
import java.util.UUID;

/**
 * The one question another context asks of a member: does this tenant hold a live member with that reference, and which
 * id is it?
 *
 * <p>A feature that owns per-member rows of its own (a voucher names its member) needs the id to scope a read and to say
 * 404 for a reference loyalty does not know. It runs inside the caller's transaction and opens none itself.
 */
public interface MemberDirectory {

    /** The live member holding {@code externalRef}, or empty when this tenant holds none. */
    Optional<MemberIdentity> find(String externalRef);

    /** A member as another context's read needs it: the opaque id and the Tenant's own reference. */
    record MemberIdentity(UUID memberId, String externalRef) {}
}
