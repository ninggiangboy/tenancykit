package dev.ngb.booking.catalog.application.session.exception;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogProblem;

/** A session still holds places a customer is committed to: {@code 409 SESSION_IN_USE}. */
public class SessionInUseException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "SESSION_IN_USE";

    public SessionInUseException() {
        super(CODE, "The session still has held or booked places.");
    }
}
