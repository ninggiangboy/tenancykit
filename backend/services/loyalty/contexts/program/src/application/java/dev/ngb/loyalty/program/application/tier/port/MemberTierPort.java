package dev.ngb.loyalty.program.application.tier.port;

/**
 * Whether any member currently holds a tier code, which decides whether the code may leave the list: a member's stored
 * tier has to keep naming a row that exists.
 *
 * <p>The points and tier evaluation side owns the answer; this context only asks the question.
 */
public interface MemberTierPort {

    boolean isHeld(String tierCode);
}
