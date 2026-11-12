package dev.ngb.core.security;

/** Answers "may the current actor do this here". Every endpoint that needs a right asks it. */
public interface Authorizer {

    /** @throws dev.ngb.core.error.AccessDeniedException when the current actor lacks the code in the current scope */
    void require(String code);
}
