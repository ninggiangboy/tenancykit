package dev.ngb.email.audience.application.segment.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.application.segment.port.SegmentRepository;
import dev.ngb.email.audience.domain.segment.Segment;
import java.util.Map;

/** Creates a saved segment; the filter is validated before it is stored. */
public class CreateSegmentUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SegmentRepository segments;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateSegmentUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SegmentRepository segments,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.segments = segments;
        this.ids = ids;
        this.clock = clock;
    }

    public Segment execute(String name, Map<String, Object> filter) {
        TenantId tenantId = tenantContext.requireTenant();
        Segment segment = Segment.create(ids.newId(), tenantId, name, filter, clock.now());
        unitOfWork.transactional(() -> segments.insert(segment));
        return segment;
    }
}
