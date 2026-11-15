package dev.ngb.email.shared;

/**
 * The two kinds of email this app sends. The kind decides which suppression scope applies: a transactional email
 * (a password reset) must not be blocked by an unsubscribe, while a marketing email must.
 */
public enum EmailKind {

    TRANSACTIONAL,
    MARKETING;

    /** A marketing email honours a {@code MARKETING} suppression; a transactional one honours only {@code ALL}. */
    public boolean marketing() {
        return this == MARKETING;
    }
}
