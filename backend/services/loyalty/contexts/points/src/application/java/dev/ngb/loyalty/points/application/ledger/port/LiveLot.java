package dev.ngb.loyalty.points.application.ledger.port;

import java.time.Instant;
import java.util.UUID;

/** A lot that still holds points: what it was granted for, how much is left and when it expires. */
public record LiveLot(UUID lotId, UUID orderId, long remaining, Instant expiresAt) {}
