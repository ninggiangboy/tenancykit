package dev.ngb.platform.access.application.permission.exception;

/** A permission code is not in the catalog, or not valid in the scope it was used in. */
public class UnknownPermissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownPermissionException(String code, String reason) {
        super("permission " + code + " " + reason);
    }
}
