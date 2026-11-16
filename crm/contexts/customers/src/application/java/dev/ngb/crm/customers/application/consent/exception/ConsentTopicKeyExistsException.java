package dev.ngb.crm.customers.application.consent.exception;

/** A topic with that key already exists in this tenant: the key identifies the topic and is immutable. */
public class ConsentTopicKeyExistsException extends ConsentProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CONSENT_TOPIC_KEY_EXISTS";

    public ConsentTopicKeyExistsException() {
        super(CODE, "A consent topic with that key already exists here.");
    }
}
