package dev.ngb.attribution.links.adapter.settings.persistence;

import static dev.ngb.attribution_jooq.Tables.ATTRIBUTION_SETTINGS;

import dev.ngb.attribution.links.application.settings.port.SettingsWriter;
import dev.ngb.attribution.links.application.settings.query.SettingsReadRepository;
import dev.ngb.attribution.links.application.settings.query.SettingsView;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * A tenant's settings row. The row is created lazily, so a tenant that never changed a setting inherits the deployment
 * defaults and a new default reaches it without a backfill.
 *
 * <p>The read merges the row (if any) with the defaults the caller passes, so a member the tenant never set answers with
 * the deployment's value rather than null.
 */
public class JooqSettingsRepository implements SettingsReadRepository, SettingsWriter {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqSettingsRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public SettingsView load(String defaultClickParam, int defaultWindowDays) {
        var row = dsl.selectFrom(ATTRIBUTION_SETTINGS)
                .where(ATTRIBUTION_SETTINGS.TENANT_ID.eq(tenantContext.requireTenant().value()))
                .fetchOne();
        if (row == null) {
            return new SettingsView(defaultClickParam, List.of(), defaultWindowDays);
        }
        String clickParam = row.getClickParam() == null || row.getClickParam().isBlank()
                ? defaultClickParam
                : row.getClickParam();
        List<String> origins = row.getAllowedOrigins() == null ? List.of() : Arrays.asList(row.getAllowedOrigins());
        Integer window = row.getDefaultWindowDays() == null ? defaultWindowDays : row.getDefaultWindowDays();
        return new SettingsView(clickParam, origins, window);
    }

    @Override
    public void save(
            UUID tenantId, String clickParam, List<String> allowedOrigins, Integer defaultWindowDays, Instant now) {
        // Only the members the caller set are written, so a partial change does not reset one it did not name. A row is
        // created on first save and then updated, so the first change and a later one take the same path.
        dsl.insertInto(ATTRIBUTION_SETTINGS)
                .set(ATTRIBUTION_SETTINGS.TENANT_ID, tenantId)
                .set(ATTRIBUTION_SETTINGS.CLICK_PARAM, clickParam)
                .set(
                        ATTRIBUTION_SETTINGS.ALLOWED_ORIGINS,
                        allowedOrigins == null ? null : allowedOrigins.toArray(String[]::new))
                .set(ATTRIBUTION_SETTINGS.DEFAULT_WINDOW_DAYS, defaultWindowDays)
                .set(ATTRIBUTION_SETTINGS.VERSION, 1L)
                .set(ATTRIBUTION_SETTINGS.UPDATED_AT, now)
                .onConflict(ATTRIBUTION_SETTINGS.TENANT_ID)
                .doUpdate()
                .set(
                        ATTRIBUTION_SETTINGS.CLICK_PARAM,
                        clickParam == null
                                ? ATTRIBUTION_SETTINGS.CLICK_PARAM
                                : DSL.val(clickParam))
                .set(
                        ATTRIBUTION_SETTINGS.ALLOWED_ORIGINS,
                        allowedOrigins == null
                                ? ATTRIBUTION_SETTINGS.ALLOWED_ORIGINS
                                : DSL.val(allowedOrigins.toArray(String[]::new)))
                .set(
                        ATTRIBUTION_SETTINGS.DEFAULT_WINDOW_DAYS,
                        defaultWindowDays == null
                                ? ATTRIBUTION_SETTINGS.DEFAULT_WINDOW_DAYS
                                : DSL.val(defaultWindowDays))
                .set(ATTRIBUTION_SETTINGS.VERSION, ATTRIBUTION_SETTINGS.VERSION.plus(1))
                .set(ATTRIBUTION_SETTINGS.UPDATED_AT, now)
                .execute();
    }
}
