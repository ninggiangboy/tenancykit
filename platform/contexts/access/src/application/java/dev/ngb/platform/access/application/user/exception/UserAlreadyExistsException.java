package dev.ngb.platform.access.application.user.exception;

/** The user already exists in this tenant. */
public class UserAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserAlreadyExistsException(String email) {
        super("a user with this email already exists: " + email);
    }
}
