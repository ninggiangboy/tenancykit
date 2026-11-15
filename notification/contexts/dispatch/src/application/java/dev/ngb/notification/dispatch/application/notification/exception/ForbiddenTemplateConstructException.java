package dev.ngb.notification.dispatch.application.notification.exception;

/**
 * A template part contains a section, an inverted section or a partial. Those are logic, and a stored override is
 * refused them at write time and at load time, so a row inserted by hand cannot smuggle any in.
 */
public class ForbiddenTemplateConstructException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenTemplateConstructException(String construct) {
        super("a template may not contain " + construct + ": templates are logic-less");
    }
}
