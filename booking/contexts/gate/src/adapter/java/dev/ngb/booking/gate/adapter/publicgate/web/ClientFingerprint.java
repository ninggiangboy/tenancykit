package dev.ngb.booking.gate.adapter.publicgate.web;

import dev.ngb.core.visitor.DailyVisitorHash;
import java.time.Instant;

/**
 * The per-instance address fingerprint: the daily visitor hash, so a burst limiter never stores or logs an address and
 * two tenants' identical visitors do not share a bucket.
 */
public final class ClientFingerprint {

    private final DailyVisitorHash hashes;

    public ClientFingerprint(String secret) {
        this.hashes = new DailyVisitorHash(secret);
    }

    public String of(String tenantId, String address, String userAgent, Instant now) {
        return hashes.of(tenantId, address, userAgent == null ? "" : userAgent, now);
    }
}
