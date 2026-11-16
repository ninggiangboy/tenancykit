package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Erases one visitor's collected rows: page views, sessions, bindings, and the clicks only they were bound to.
 *
 * <p>Rollups hold aggregates only and are untouched, so a report's totals stay and the person does not. A conversion
 * keeps its amount and loses its visitor and click in a later phase; this endpoint removes what the collection owns.
 */
@RestController
@RequestMapping("/api/v1/data-subjects")
public class DataSubjectController {

    private final ErasureStore erasure;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;

    public DataSubjectController(ErasureStore erasure, UnitOfWork unitOfWork, TenantContext tenantContext) {
        this.erasure = erasure;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/erasure")
    @RequiresPermission(AttributionPermissions.DATA_ERASE)
    ErasureView erase(@RequestBody ErasureRequest request) {
        tenantContext.requireTenant();
        ErasureStore.Removed removed =
                unitOfWork.transactional(() -> erasure.erase(request == null ? null : request.visitor_id()));
        return new ErasureView(
                removed.pageViews(), removed.sessions(), removed.bindings(), removed.clicks(), 0);
    }

    record ErasureRequest(String visitor_id) {}

    record ErasureView(int page_events, int sessions, int bindings, int clicks, int conversions) {}
}
