package dev.ngb.booking.catalog.application.catalog.exception;

/** A body that is well formed but violates a catalog rule: {@code 422 VALIDATION_FAILED}. */
public class CatalogValidationException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public CatalogValidationException(String message) {
        super(CODE, message);
    }
}
