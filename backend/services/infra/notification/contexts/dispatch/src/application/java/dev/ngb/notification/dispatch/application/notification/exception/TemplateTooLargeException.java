package dev.ngb.notification.dispatch.application.notification.exception;

/** A part or a subject exceeds the limit the email contract sets, so it is refused rather than truncated. */
public class TemplateTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TemplateTooLargeException(String part, int length, int limit) {
        super(part + " is " + length + " units long; the limit is " + limit);
    }
}
