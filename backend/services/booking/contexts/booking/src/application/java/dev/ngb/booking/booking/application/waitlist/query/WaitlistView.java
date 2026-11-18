package dev.ngb.booking.booking.application.waitlist.query;

import java.time.Instant;
import java.util.UUID;

/** One waiting entry as the GET handlers answer it; {@code position} counts the waiting entries ahead of it. */
public record WaitlistView(
        UUID id,
        UUID offeringId,
        String customerRef,
        int qty,
        String status,
        int position,
        UUID sessionId,
        String poolCode,
        Instant startsAt,
        Instant endsAt,
        UUID offeredBookingId,
        Instant offeredUntil,
        Instant createdAt) {}
