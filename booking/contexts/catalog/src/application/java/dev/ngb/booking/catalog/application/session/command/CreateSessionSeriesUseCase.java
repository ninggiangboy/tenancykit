package dev.ngb.booking.catalog.application.session.command;

import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.service.SessionMaterializer;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.booking.catalog.domain.session.SessionSeriesId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creates a repeating capacity series and materializes it into the offering's horizon.
 *
 * <p>The offering must be one that sells places, and the rule must be one this service expands. The series is stored
 * first and the sessions follow in the same transaction, so a rule that cannot be expanded leaves neither behind.
 */
public class CreateSessionSeriesUseCase {

    /** A series as the Tenant sent it; {@code pools} is empty when it named none. */
    public record Command(
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            Integer durationMinutes,
            Integer capacity,
            List<Pool> pools) {}

    /** The series, the sessions it materialized, the future sessions a change left alone, and its pools. */
    public record Result(SessionSeries series, List<Session> sessions, List<UUID> untouchedSessions, List<Pool> pools) {}

    private final UnitOfWork unitOfWork;
    private final SessionRepository sessions;
    private final SessionRules rules;
    private final SessionMaterializer materializer;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateSessionSeriesUseCase(
            UnitOfWork unitOfWork,
            SessionRepository sessions,
            SessionRules rules,
            SessionMaterializer materializer,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.sessions = sessions;
        this.rules = rules;
        this.materializer = materializer;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            rules.requireCapacityOffering(command.offeringId());
            String zone = SessionRules.zone(command.zone());
            String dtstart = SessionRules.dtstart(command.dtstart());
            List<String> rule = SessionRules.lines(command.rule());
            int durationMinutes = SessionRules.requiredPositive(command.durationMinutes(), "durationMinutes");
            int capacity = SessionRules.requiredPositive(command.capacity(), "capacity");
            List<Pool> pools = SessionRules.pools(command.pools(), capacity);
            Instant now = clock.now();
            SessionSeries series = SessionSeries.create(
                    new SessionSeriesId(ids.newId()),
                    tenantContext.requireTenant(),
                    command.offeringId(),
                    zone,
                    dtstart,
                    rule,
                    durationMinutes,
                    capacity,
                    now);
            sessions.insertSeries(series);
            List<Session> materialized = materializer.materialize(series, pools, now);
            return new Result(series, materialized, List.of(), pools);
        });
    }
}
