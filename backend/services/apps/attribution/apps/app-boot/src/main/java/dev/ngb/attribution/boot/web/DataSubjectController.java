package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.tracking.api.erasure.ConversionPersonEraser;
import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Data subjects",
        description =
                "Erasing one visitor's collected data.")
@RestController
@RequestMapping("/api/v1/data-subjects")
public class DataSubjectController {

    private final ErasureStore erasure;
    private final ConversionPersonEraser conversions;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;

    public DataSubjectController(
            ErasureStore erasure,
            ConversionPersonEraser conversions,
            UnitOfWork unitOfWork,
            TenantContext tenantContext) {
        this.erasure = erasure;
        this.conversions = conversions;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
    }

    @Operation(
            summary = "Erase a visitor's collected data",
            description =
                    "Erases one visitor's (`visitor_id`) collected rows: page events, sessions, bindings and the clicks "
                    + "only that visitor was bound to, and answers how many of each were removed. Rollups hold aggregates "
                    + "only, so a report's totals stay and the person does not. Requires `attribution:data:erase`.")
    @PostMapping("/erasure")
    @RequiresPermission(AttributionPermissions.DATA_ERASE)
    ErasureView erase(@RequestBody ErasureRequest request) {
        tenantContext.requireTenant();
        String visitorId = request == null ? null : request.visitor_id();
        ErasureStore.Removed removed = unitOfWork.transactional(() -> erasure.erase(visitorId));
        // The conversions lose their person, not their amount: a report's revenue stays and the visitor does not.
        int conversionsErased = unitOfWork.transactional(() -> conversions.erase(visitorId));
        return new ErasureView(
                removed.pageViews(), removed.sessions(), removed.bindings(), removed.clicks(), conversionsErased);
    }

    record ErasureRequest(String visitor_id) {}

    record ErasureView(int page_events, int sessions, int bindings, int clicks, int conversions) {}
}
