package dev.ngb.survey.authoring.application.settings.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.UUID;

/** The tenant's survey defaults, read inside a tenant-scoped transaction. */
public class SurveySettingsQueries {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SurveySettingsReadRepository settings;

    public SurveySettingsQueries(
            UnitOfWork unitOfWork, TenantContext tenantContext, SurveySettingsReadRepository settings) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.settings = settings;
    }

    /** The configured row, or the empty defaults a tenant that never changed one shows. */
    public SurveySettingsReadRepository.SettingsView get() {
        return unitOfWork.transactional(() -> {
            UUID tenantId = tenantContext.requireTenant().value();
            return settings.find(tenantId)
                    .orElseGet(() -> new SurveySettingsReadRepository.SettingsView(null, null, null, null, null, 0));
        });
    }
}
