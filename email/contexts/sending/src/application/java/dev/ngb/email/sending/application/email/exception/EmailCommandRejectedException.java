package dev.ngb.email.sending.application.email.exception;

/** The producer's own command failed its contract check; the message is fixed and never carries a value. */
public class EmailCommandRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailCommandRejectedException() {
        super("the email command does not match contracts/commands/email.send.schema.json");
    }
}
