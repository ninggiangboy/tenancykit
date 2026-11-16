package dev.ngb.booking.catalog.application.catalog.exception;

/** The Tenant already chose this code for another object in the tenant: {@code 409 DUPLICATE_CODE}. */
public class DuplicateCodeException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "DUPLICATE_CODE";

    public DuplicateCodeException(String code) {
        super(CODE, "A catalog object already uses the code " + code + " in this tenant.");
    }
}
