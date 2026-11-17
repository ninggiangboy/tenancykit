package dev.ngb.booking.gate.application.settings.query;

/** The tenant timing values the settings handler reports, copied from the app's configuration at wiring time. */
public record GateTimingView(
        long holdTtlSeconds,
        long maxExtensionSeconds,
        long graceSeconds,
        long offerWindowSeconds,
        long horizonDays,
        long tokenTtlCapSeconds,
        int rateLimitPerMinute,
        int admissionCap,
        int waitlistCapPerTarget) {}
