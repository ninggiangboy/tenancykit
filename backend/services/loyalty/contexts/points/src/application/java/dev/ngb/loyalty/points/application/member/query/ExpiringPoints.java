package dev.ngb.loyalty.points.application.member.query;

import java.time.Instant;

/** Points that expire at one moment, grouped so a member read shows one line per expiry date. */
public record ExpiringPoints(Instant expiresAt, long points) {}
