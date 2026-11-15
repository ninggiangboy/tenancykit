package dev.ngb.email.content.api.render;

/**
 * A template read a variable the caller did not supply. The message names the variable and nothing else, so a failed
 * campaign email reports exactly what the Tenant must fix without leaking the receiver's data.
 */
public class MissingTemplateVariableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String variable;

    public MissingTemplateVariableException(String variable) {
        super("the template needs a value for " + variable);
        this.variable = variable;
    }

    public String variable() {
        return variable;
    }
}
