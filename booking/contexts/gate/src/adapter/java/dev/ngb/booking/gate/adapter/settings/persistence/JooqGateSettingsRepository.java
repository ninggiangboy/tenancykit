package dev.ngb.booking.gate.adapter.settings.persistence;

import static dev.ngb.booking_jooq.Tables.GATE_SETTINGS;

import dev.ngb.booking.gate.application.settings.service.GateSettings;
import dev.ngb.booking.gate.application.settings.port.GateSettingsRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** One row per tenant; row-level security makes the tenant the only one this can read or write. */
public class JooqGateSettingsRepository implements GateSettingsRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final Clock clock;

    public JooqGateSettingsRepository(DSLContext dsl, TenantContext tenantContext, Clock clock) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public Optional<GateSettings> find() {
        return dsl.select(GATE_SETTINGS.ALLOWED_ORIGINS, GATE_SETTINGS.MAX_TOKEN_TTL_SECONDS)
                .from(GATE_SETTINGS)
                .fetchOptional()
                .map(row -> new GateSettings(
                        row.value1() == null ? List.of() : Arrays.asList(row.value1()),
                        row.value2()));
    }

    @Override
    public void save(List<String> allowedOrigins, int maxTokenTtlSeconds) {
        dsl.insertInto(GATE_SETTINGS)
                .set(GATE_SETTINGS.TENANT_ID, tenantContext.requireTenant().value())
                .set(GATE_SETTINGS.ALLOWED_ORIGINS, allowedOrigins.toArray(String[]::new))
                .set(GATE_SETTINGS.MAX_TOKEN_TTL_SECONDS, maxTokenTtlSeconds)
                .set(GATE_SETTINGS.UPDATED_AT, clock.now())
                .onConflict(GATE_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(GATE_SETTINGS.ALLOWED_ORIGINS, allowedOrigins.toArray(String[]::new))
                .set(GATE_SETTINGS.MAX_TOKEN_TTL_SECONDS, maxTokenTtlSeconds)
                .set(GATE_SETTINGS.UPDATED_AT, clock.now())
                .execute();
    }
}
