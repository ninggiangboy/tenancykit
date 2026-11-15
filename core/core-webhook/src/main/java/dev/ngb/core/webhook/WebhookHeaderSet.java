package dev.ngb.core.webhook;

/**
 * The headers of a signed webhook delivery. The signature is {@code v1=} followed by the lowercase hex HMAC-SHA256 of
 * {@code timestamp + "." + rawBody}, keyed with the endpoint's signing secret string.
 *
 * <p>Each app has its own header set, so a receiver telling one app's delivery from another's needs only the prefix,
 * and an app that adds a delivery cannot silently change another's wire format. The names themselves are
 * {@link dev.ngb.core.http.HttpHeaders}'s, so a receiver that reads a header off the wire and a sender that sets it
 * cannot drift apart.
 *
 * @param eventId the event id, also the deduplication key
 * @param eventType the event type
 * @param timestamp the epoch second the delivery was signed at
 * @param keyId the id of the signing key
 * @param signature the signed digest header
 * @param attempt which attempt this delivery is, starting at 1
 */
public record WebhookHeaderSet(String eventId, String eventType, String timestamp, String keyId, String signature, String attempt) {

    /** The version prefix of the signature header value. */
    public static final String SIGNATURE_VERSION = "v1";

    /** Billing's set, the six {@code Billing-*} headers a Tenant already verifies. */
    public static final WebhookHeaderSet BILLING = new WebhookHeaderSet(
            dev.ngb.core.http.HttpHeaders.BILLING_EVENT_ID,
            dev.ngb.core.http.HttpHeaders.BILLING_EVENT_TYPE,
            dev.ngb.core.http.HttpHeaders.BILLING_TIMESTAMP,
            dev.ngb.core.http.HttpHeaders.BILLING_KEY_ID,
            dev.ngb.core.http.HttpHeaders.BILLING_SIGNATURE,
            dev.ngb.core.http.HttpHeaders.BILLING_ATTEMPT);

    /** Survey's set: the same six ideas under a {@code Survey-*} prefix. */
    public static final WebhookHeaderSet SURVEY = new WebhookHeaderSet(
            dev.ngb.core.http.HttpHeaders.SURVEY_EVENT_ID,
            dev.ngb.core.http.HttpHeaders.SURVEY_EVENT_TYPE,
            dev.ngb.core.http.HttpHeaders.SURVEY_TIMESTAMP,
            dev.ngb.core.http.HttpHeaders.SURVEY_KEY_ID,
            dev.ngb.core.http.HttpHeaders.SURVEY_SIGNATURE,
            dev.ngb.core.http.HttpHeaders.SURVEY_ATTEMPT);
}
