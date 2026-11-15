package dev.ngb.notification.dispatch.application.notification.exception;

/** A template named a variable the model does not carry; JMustache's default of an empty string would send a hole. */
public class MissingTemplateVariableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingTemplateVariableException(String variable) {
        super("the template refers to an unknown variable: " + variable);
    }
}
