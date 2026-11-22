package dev.ngb.customer.customers.application.consent.exception;

/** No consent topic with that key exists in this tenant. */
public class ConsentTopicNotFoundException extends ConsentProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CONSENT_TOPIC_NOT_FOUND";

    public ConsentTopicNotFoundException() {
        super(CODE, "No consent topic exists here with that key.");
    }
}
