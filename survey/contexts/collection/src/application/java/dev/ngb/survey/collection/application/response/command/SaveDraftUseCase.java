package dev.ngb.survey.collection.application.response.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.response.port.ResponseRepository;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.response.Response;
import dev.ngb.survey.collection.application.response.exception.AlreadySubmittedException;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.collection.domain.visit.VisitState;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Saves a respondent's partial answers, creating the in-progress response on the first save and moving the visit to
 * {@link VisitState#STARTED}.
 *
 * <p>The response records the version the visit pinned, so a resume after a newer version is published keeps the
 * questions it opened with. It is the browser's own draft: its visit token is its identity, so no response id is ever
 * exposed to it before the submit.
 */
public class SaveDraftUseCase {

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final ResponseRepository responses;
    private final IdGenerator ids;
    private final Clock clock;

    public SaveDraftUseCase(
            UnitOfWork unitOfWork,
            VisitRepository visits,
            ResponseRepository responses,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.responses = responses;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param visit the visit the browser's token names, already resolved
     * @param link the link that visit came from, which supplies the source and the personal reference
     * @param answers the partial answers, stored without validation because a draft may be incomplete
     * @return the stored draft
     */
    public Response execute(Visit visit, SurveyLink link, Map<String, Object> answers) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Optional<Response> stored = responses.findByVisitId(visit.id());
            Response draft = stored
                    .map(existing -> existing.withAnswers(answers))
                    .orElseGet(() -> Response.draft(
                                    ids.newId(),
                                    visit.tenantId(),
                                    visit.surveyId(),
                                    visit.versionId(),
                                    visit.linkId(),
                                    visit.id(),
                                    null,
                                    link.source(),
                                    link.externalRef(),
                                    link.personal() ? link.id() : null,
                                    now)
                            .withAnswers(answers));

            if (draft.status() != dev.ngb.survey.collection.domain.response.ResponseStatus.IN_PROGRESS) {
                throw new AlreadySubmittedException();
            }
            if (stored.isEmpty()) {
                responses.insert(draft);
            } else {
                responses.saveDraft(draft);
            }

            if (visit.state() == VisitState.OPENED) {
                Visit started = visit.started(draft.id(), now);
                visits.save(started, visit.state().name());
            }
            return draft;
        });
    }
}
