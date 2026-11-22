package dev.ngb.survey.collection.application.link.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.collection.application.link.exception.LinkRefusedException;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.application.link.service.SurveyState;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues one personal link per recipient in one batch, which is what an imported mailroom segment becomes.
 *
 * <p>A duplicate reference in the request is refused before anything is written, because two links for one recipient
 * would let a single-use link be completed twice under two ids.
 */
public class CreatePersonalLinksUseCase {

    /** The most links one request may issue; a bigger import is several requests. */
    public static final int MAX_LINKS = 1000;

    /** One recipient: the reference the Tenant knows them by, an optional source and an optional expiry. */
    public record Recipient(String externalRef, String source, Instant expiresAt) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;
    private final SurveyState surveyState;
    private final IdGenerator ids;
    private final Clock clock;

    public CreatePersonalLinksUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            SurveyState surveyState,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
        this.surveyState = surveyState;
        this.ids = ids;
        this.clock = clock;
    }

    public List<SurveyLink> execute(UUID surveyId, String defaultSource, List<Recipient> recipients) {
        if (recipients.isEmpty() || recipients.size() > MAX_LINKS) {
            throw new IllegalArgumentException("a bulk personal import issues 1 to " + MAX_LINKS + " links");
        }
        return unitOfWork.transactional(() -> {
            TenantId tenant = tenantContext.requireTenant();
            surveyState.requireOpen(surveyId);

            Set<String> references = new HashSet<>();
            for (Recipient recipient : recipients) {
                if (recipient.externalRef() == null || recipient.externalRef().isBlank()) {
                    throw new IllegalArgumentException("every personal link names the recipient it is for");
                }
                if (!references.add(recipient.externalRef())) {
                    throw new LinkRefusedException(
                            LinkRefusedException.Reason.DUPLICATE_REFERENCE,
                            "the request names the same recipient twice");
                }
            }

            Instant now = clock.now();
            List<SurveyLink> issued = new ArrayList<>(recipients.size());
            for (Recipient recipient : recipients) {
                String source = recipient.source() == null || recipient.source().isBlank()
                        ? defaultSource
                        : recipient.source();
                issued.add(SurveyLink.personalLink(
                        ids.newId(),
                        tenant,
                        surveyId,
                        source,
                        recipient.externalRef(),
                        recipient.expiresAt(),
                        now));
            }
            links.insertAll(issued);
            return List.copyOf(issued);
        });
    }
}
