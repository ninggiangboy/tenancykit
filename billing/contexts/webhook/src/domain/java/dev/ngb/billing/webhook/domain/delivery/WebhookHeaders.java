package dev.ngb.billing.webhook.domain.delivery;

import dev.ngb.core.http.HttpHeaders;

/**
 * The headers of a delivery. The signature is {@code v1=} followed by the lowercase hex HMAC-SHA256 of
 * {@code timestamp + "." + rawBody}, keyed with the endpoint's signing secret string.
 *
 * <p>The names themselves are {@link HttpHeaders}'s, so a receiver that reads the header off the wire and a
 * sender that sets it cannot drift apart.
 */
public final class WebhookHeaders {

    public static final String EVENT_ID = HttpHeaders.BILLING_EVENT_ID;
    public static final String EVENT_TYPE = HttpHeaders.BILLING_EVENT_TYPE;
    public static final String TIMESTAMP = HttpHeaders.BILLING_TIMESTAMP;
    public static final String KEY_ID = HttpHeaders.BILLING_KEY_ID;
    public static final String SIGNATURE = HttpHeaders.BILLING_SIGNATURE;
    public static final String ATTEMPT = HttpHeaders.BILLING_ATTEMPT;
    public static final String SIGNATURE_VERSION = "v1";

    private WebhookHeaders() {}
}
