package dev.ngb.booking.gate.application.settings.service;

import java.util.List;

/** The Tenant-owned part of the public edge: who may call it from a browser, and how long its tokens may live. */
public record GateSettings(List<String> allowedOrigins, int maxTokenTtlSeconds) {

    public GateSettings {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
