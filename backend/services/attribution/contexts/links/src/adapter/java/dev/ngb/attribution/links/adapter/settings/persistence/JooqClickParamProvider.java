package dev.ngb.attribution.links.adapter.settings.persistence;

import static dev.ngb.attribution_jooq.Tables.ATTRIBUTION_SETTINGS;

import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;

/**
 * The click parameter a tenant's links use, from its settings row or the deployment default.
 *
 * <p>The row is created lazily: a tenant that never changed a setting has none, and its parameter is the deployment's
 * {@code aid}. Reading it here keeps the redirect and the save-time collision check on one source. It lives in the
 * settings package because the settings table is the wiring layer's, not a link's.
 */
public class JooqClickParamProvider implements ClickParamProvider {

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final String defaultParam;

    public JooqClickParamProvider(DSLContext dsl, TenantContext tenantContext, String defaultParam) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.defaultParam = defaultParam;
    }

    @Override
    public String current() {
        String configured = dsl.select(ATTRIBUTION_SETTINGS.CLICK_PARAM)
                .from(ATTRIBUTION_SETTINGS)
                .where(ATTRIBUTION_SETTINGS.TENANT_ID.eq(tenantContext.requireTenant().value()))
                .fetchOne(ATTRIBUTION_SETTINGS.CLICK_PARAM);
        return configured == null || configured.isBlank() ? defaultParam : configured;
    }
}
