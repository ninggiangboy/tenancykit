package dev.ngb.booking.webhook.application.delivery.service;

import dev.ngb.core.webhook.WebhookHeaderSet;

/**
 * The header set of a booking delivery: the six ideas every suite webhook carries, under booking's own {@code Booking-*}
 * prefix, so a receiver tells a booking delivery from another app's by the prefix alone.
 *
 * <p>It lives in the app rather than in {@code core-webhook} because a header set is the producer's wire format: the
 * core keeps the signing rule, and each app names the headers it sends.
 */
public final class BookingWebhookHeaders {

    public static final WebhookHeaderSet BOOKING = new WebhookHeaderSet(
            "Booking-Event-Id", "Booking-Event-Type", "Booking-Timestamp", "Booking-Key-Id", "Booking-Signature", "Booking-Attempt");

    private BookingWebhookHeaders() {}
}
