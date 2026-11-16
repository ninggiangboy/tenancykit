package dev.ngb.booking.catalog.application.catalog.exception;

/** The resource is referenced by an offering or a group and may only be archived: {@code 409 RESOURCE_IN_USE}. */
public class ResourceInUseException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "RESOURCE_IN_USE";

    public ResourceInUseException() {
        super(CODE, "The resource is referenced; archive it instead of deleting it.");
    }
}
