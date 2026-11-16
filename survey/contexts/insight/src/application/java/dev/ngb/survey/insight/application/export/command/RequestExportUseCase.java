package dev.ngb.survey.insight.application.export.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.insight.application.export.port.ExportRepository;
import dev.ngb.survey.insight.domain.export.Export;
import java.util.Map;
import java.util.UUID;

/**
 * Queues an export for the worker, which is the only place a CSV is produced.
 *
 * <p>Queuing rather than streaming from the request keeps a large export off the API's connection and lets a Tenant ask
 * for one it can come back to. The filter is stored with it so the same request is repeatable.
 */
public class RequestExportUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ExportRepository exports;
    private final IdGenerator ids;
    private final Clock clock;

    public RequestExportUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ExportRepository exports,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.exports = exports;
        this.ids = ids;
        this.clock = clock;
    }

    public Export execute(UUID surveyId, Map<String, Object> filter, String requestedBy) {
        return unitOfWork.transactional(() -> {
            Export export = Export.queued(
                    ids.newId(),
                    tenantContext.requireTenant().value(),
                    surveyId,
                    filter == null ? Map.of() : filter,
                    requestedBy,
                    clock.now());
            exports.insert(export);
            return export;
        });
    }
}
