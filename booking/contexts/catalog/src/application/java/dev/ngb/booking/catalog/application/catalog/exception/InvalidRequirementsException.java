package dev.ngb.booking.catalog.application.catalog.exception;

/** An offering's requirements name one resource twice, name an archived resource, or fit the wrong inventory kind. */
public class InvalidRequirementsException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "INVALID_REQUIREMENTS";

    public InvalidRequirementsException(String message) {
        super(CODE, message);
    }
}
