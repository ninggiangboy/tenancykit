package dev.ngb.platform.access.application.user.exception;

/** The user does not exist. */
public class UserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserNotFoundException(String id) {
        super("no user " + id);
    }
}
