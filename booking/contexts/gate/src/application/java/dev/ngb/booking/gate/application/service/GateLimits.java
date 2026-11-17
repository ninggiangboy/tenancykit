package dev.ngb.booking.gate.application.service;

/** The limits the public edge reads from configuration: one bucket per fingerprint and one cap per target. */
public record GateLimits(int rateLimitPerMinute, int admissionCap, int maxBodyBytes) {}
