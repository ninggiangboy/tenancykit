package dev.ngb.booking.webhook.application.delivery.port;

public interface PayloadSigner {

    /** @return the {@code Booking-Signature} header value for the body signed at {@code timestampSeconds} */
    String sign(String secret, long timestampSeconds, byte[] body);
}
