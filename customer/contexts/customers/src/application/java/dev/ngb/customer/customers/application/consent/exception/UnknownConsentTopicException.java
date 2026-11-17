package dev.ngb.customer.customers.application.consent.exception;

/**
 * The record names a topic this tenant does not define, or one it archived. An archived topic keeps the records it has
 * and takes no new one, so a record against it is refused the same way as one against a key nobody defined.
 */
public class UnknownConsentTopicException extends ConsentProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CONSENT_TOPIC_UNKNOWN";

    public UnknownConsentTopicException() {
        super(CODE, "This tenant has no consent topic with that key that takes records.");
    }
}
