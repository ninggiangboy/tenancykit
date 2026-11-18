package dev.ngb.booking.gate.application.settings.port;

import dev.ngb.booking.gate.application.settings.service.GateSettings;
import java.util.List;
import java.util.Optional;

/** Stores one row per tenant; a tenant that never set the gate gets the defaults without a row. */
public interface GateSettingsRepository {

    Optional<GateSettings> find();

    void save(List<String> allowedOrigins, int maxTokenTtlSeconds);
}
