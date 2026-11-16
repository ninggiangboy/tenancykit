package dev.ngb.loyalty.shared;

/**
 * The six headers of a signed loyalty delivery, under loyalty's own prefix so a receiver tells one app's webhooks from
 * another's by the header name alone. The names live here rather than in the shared core because the core is shared by
 * every app and must not gain a per-app vocabulary; the webhook context turns them into the {@code WebhookHeaderSet}
 * that core-webhook's signer and sender already speak.
 *
 * <p>The signature is {@code v1=} followed by the lowercase hex HMAC-SHA256 of {@code timestamp + "." + rawBody}, keyed
 * with the endpoint's signing secret.
 */
public final class LoyaltyWebhookHeaders {

    /** The id of the event the delivery carries; a receiver treats a repeated id as a duplicate. */
    public static final String EVENT_ID = "Loyalty-Event-Id";

    /** The event type, one of the twelve in {@code contracts/events/loyalty}. */
    public static final String EVENT_TYPE = "Loyalty-Event-Type";

    /** The epoch second the delivery was signed at. */
    public static final String TIMESTAMP = "Loyalty-Timestamp";

    /** The id of the signing key the signature was made with. */
    public static final String KEY_ID = "Loyalty-Key-Id";

    /** The signed digest over the timestamp and the raw body. */
    public static final String SIGNATURE = "Loyalty-Signature";

    /** Which attempt this delivery is, starting at 1. */
    public static final String ATTEMPT = "Loyalty-Attempt";

    private LoyaltyWebhookHeaders() {}
}
