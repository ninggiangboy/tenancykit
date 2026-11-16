package dev.ngb.loyalty.points.application.member.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The members a request reaches by the Tenant's reference.
 *
 * <p>A member row is the first lock of every order and return flow: two calls for one member run one after the other,
 * so a replay, a return and a second order cannot each read the same debt and each write their own.
 */
public interface MemberRepository {

    /** Inserts the member when the reference is free; a reference that is already taken inserts nothing. */
    boolean addIfAbsent(MemberRow member);

    /** Reads the member by reference under a row lock, which is the first lock of every flow. */
    Optional<MemberRow> lockByRef(String externalRef);

    /** Reads the member by id under a row lock, for a flow that starts from an order. */
    Optional<MemberRow> lockById(UUID memberId);

    void update(MemberRow member);
}
