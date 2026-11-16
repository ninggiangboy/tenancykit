package dev.ngb.booking.catalog.application.catalog.exception;

/**
 * A catalog failure with the stable code its problem body carries, so a Tenant System can branch on it without
 * reading the message.
 */
public abstract class CatalogProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected CatalogProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
