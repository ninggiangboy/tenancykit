package dev.ngb.platform.access.application.role.exception;

/** The role does not exist. */
public class RoleNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RoleNotFoundException(String id) {
        super("no role " + id);
    }
}
