package dev.ngb.core.http;

/**
 * Every header name the suite puts on the wire, so no controller, filter, sender or test carries a literal.
 *
 * <p>{@code Idempotency-Key}, {@code X-Request-Id} and {@code If-Match} are the suite's request headers:
 * the key a repeated unsafe call carries, the caller's trace id, and the entity tag a conditional update
 * compares against. {@code Stripe-Signature} is the signature Stripe puts on the webhooks it sends Billing.
 *
 * <p>The six {@code Billing-*} headers are the delivery headers Billing sets on an outbound webhook and
 * Tenant verifies on its signed endpoint. The six {@code Survey-*} headers are the same set for Survey's own
 * deliveries, so a receiver distinguishes the two apps' webhooks by the header prefix alone.
 */
public final class HttpHeaders {

    /** The client's key for a repeated unsafe call. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** The caller's trace id, echoed in the answer when supplied. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** The strong entity tag a conditional update compares against. */
    public static final String IF_MATCH = "If-Match";

    /** Stripe's signature over a webhook body, verified before anything in the body is believed. */
    public static final String STRIPE_SIGNATURE = "Stripe-Signature";

    /** The id of the event a delivery carries; a receiver treats a repeated id as a duplicate. */
    public static final String BILLING_EVENT_ID = "Billing-Event-Id";

    /** The event type a delivery carries, which decides what the receiver does with it. */
    public static final String BILLING_EVENT_TYPE = "Billing-Event-Type";

    /** The epoch second the delivery was signed at. */
    public static final String BILLING_TIMESTAMP = "Billing-Timestamp";

    /** The id of the signing key the signature was made with. */
    public static final String BILLING_KEY_ID = "Billing-Key-Id";

    /** The HMAC-SHA256 of {@code timestamp + "." + rawBody}, keyed with the endpoint's secret. */
    public static final String BILLING_SIGNATURE = "Billing-Signature";

    /** Which attempt this delivery is, starting at 1. */
    public static final String BILLING_ATTEMPT = "Billing-Attempt";

    /** The id of the event a Survey delivery carries; a receiver treats a repeated id as a duplicate. */
    public static final String SURVEY_EVENT_ID = "Survey-Event-Id";

    /** The event type a Survey delivery carries. */
    public static final String SURVEY_EVENT_TYPE = "Survey-Event-Type";

    /** The epoch second the Survey delivery was signed at. */
    public static final String SURVEY_TIMESTAMP = "Survey-Timestamp";

    /** The id of the Survey signing key the signature was made with. */
    public static final String SURVEY_KEY_ID = "Survey-Key-Id";

    /** The HMAC-SHA256 of {@code timestamp + "." + rawBody}, keyed with the Survey endpoint's secret. */
    public static final String SURVEY_SIGNATURE = "Survey-Signature";

    /** Which attempt this Survey delivery is, starting at 1. */
    public static final String SURVEY_ATTEMPT = "Survey-Attempt";

    private HttpHeaders() {}
}
