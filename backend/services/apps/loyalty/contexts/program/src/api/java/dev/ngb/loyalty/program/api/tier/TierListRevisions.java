package dev.ngb.loyalty.program.api.tier;

/**
 * Whether the tier list changed since the evaluation last looked at every member.
 *
 * <p>A replace of the list moves the mark, and the run that decides every member's tier clears it. It is a mark rather
 * than a list of members, so the run advances member by member and a run that stops halfway is finished by the next one
 * instead of starting over.
 */
public interface TierListRevisions {

    /** True while a tier change has not been taken to every member yet. */
    boolean reviewPending();

    /** Records that every member has been taken to the current list. */
    void reviewed();
}
