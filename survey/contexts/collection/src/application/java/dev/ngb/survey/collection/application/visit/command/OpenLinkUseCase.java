package dev.ngb.survey.collection.application.visit.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.application.link.service.SurveyState;
import dev.ngb.survey.collection.application.visit.exception.AlreadyCompletedException;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.application.visit.service.ArrivalFacts;
import dev.ngb.survey.collection.application.visit.service.OpenedLink;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens a link: verifies the link and the survey still open it, pins the version the visit will run, creates the visit
 * and returns the token the browser uses from here on.
 *
 * <p>Every reason a link does not open — a malformed token, an unknown id, a revoked link, an expired one, another
 * tenant's — answers the same not-found. That indifference is part of the contract: a 404 that differed by cause would
 * let a caller probe for live links and for one tenant's ids.
 *
 * <p>A personal link that already completed refuses a second opening, and one with an open visit returns that visit
 * rather than creating a duplicate, so a recipient who reopens the same mail continues where they stopped.
 */
public class OpenLinkUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;
    private final VisitRepository visits;
    private final SurveyState surveyState;
    private final PublishedVersionReader versions;
    private final ArrivalFacts arrivalFacts;
    private final StartVisitUseCase startVisit;
    private final Clock clock;

    public OpenLinkUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            VisitRepository visits,
            SurveyState surveyState,
            PublishedVersionReader versions,
            ArrivalFacts arrivalFacts,
            StartVisitUseCase startVisit,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
        this.visits = visits;
        this.surveyState = surveyState;
        this.versions = versions;
        this.arrivalFacts = arrivalFacts;
        this.startVisit = startVisit;
        this.clock = clock;
    }

    /**
     * @param linkId the link a verified token names
     * @param request what the browser sent: the referrer, the query labels, the user agent and the client address
     * @return the visit and the token to present next, or empty when the link no longer opens
     */
    public Optional<OpenedLink> execute(UUID linkId, ArrivalRequest request) {
        TenantId tenant = tenantContext.requireTenant();
        Instant now = clock.now();
        return unitOfWork.transactional(() -> {
            SurveyLink link = links.findById(linkId).orElseThrow(OpenLinkUseCase::notFound);
            if (!link.opensAt(now)) {
                return Optional.<OpenedLink>empty();
            }
            surveyState.requireOpen(link.surveyId());

            if (link.personal()) {
                if (visits.completedForLink(link.id())) {
                    throw new AlreadyCompletedException();
                }
                Optional<OpenedLink> resumed = visits.openVisitOfLink(link.id())
                        .map(visit -> new OpenedLink(link, visit, null));
                if (resumed.isPresent()) {
                    return resumed;
                }
            }

            PublishedVersionReader.PublishedVersion version = versions.current(link.surveyId())
                    .orElseThrow(OpenLinkUseCase::notFound);
            StartVisitUseCase.Started started = startVisit.execute(new StartVisitUseCase.Opened(
                    tenant.value(),
                    link.surveyId(),
                    link.id(),
                    version.id(),
                    version.versionNo(),
                    arrivalFacts.describe(link.source(), request)));
            return Optional.of(new OpenedLink(link, started.visit(), started.visitToken()));
        });
    }

    /** The not-found every invalid link answers, so a caller cannot tell one cause from another. */
    public static NotFoundException notFound() {
        return new NotFoundException("No link exists here.");
    }

    /**
     * What the browser contributed to the arrival, before it is reduced to bounded facts.
     *
     * @param referrer the {@code Referer} header, or null
     * @param query the query parameters, from which only the five UTM labels survive
     * @param userAgent the {@code User-Agent} header, or null
     * @param clientAddress the peer address, used in memory only to resolve a country
     */
    public record ArrivalRequest(String referrer, Map<String, String> query, String userAgent, String clientAddress) {}
}
