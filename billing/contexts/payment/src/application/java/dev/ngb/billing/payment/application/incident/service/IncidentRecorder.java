package dev.ngb.billing.payment.application.incident.service;

import dev.ngb.billing.payment.application.incident.port.IncidentRepository;
import dev.ngb.billing.payment.domain.incident.IncidentKind;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.Objects;

/**
 * The one way a finding is recorded: the id, the time and the transaction are supplied here, so a caller says what
 * was found and for which configuration. It joins the caller's transaction when there is one.
 */
public class IncidentRecorder {

    private final UnitOfWork unitOfWork;
    private final IncidentRepository incidents;
    private final IdGenerator ids;
    private final Clock clock;

    public IncidentRecorder(UnitOfWork unitOfWork, IncidentRepository incidents, IdGenerator ids, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void record(TenantId tenantId, StripeConfigurationId configurationId, IncidentKind kind, String subject, String detail) {
        unitOfWork.transactional(() -> incidents.record(ids.newId(), tenantId, configurationId, kind, subject, detail, clock.now()));
    }
}
