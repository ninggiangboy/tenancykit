package dev.ngb.survey.insight.application.respondent.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.VisitRow;
import dev.ngb.survey.insight.application.respondent.query.RespondentView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The respondents list: one row per arrival, optionally narrowed to a state or a source.
 *
 * <p>A completed visit takes its identity from the response it produced; a visit that stopped before finishing has only
 * the personal link's reference, because a public link never names a person. That is why the list is what a Tenant uses
 * to decide who to chase, and not a claim about who the person is.
 */
public class RespondentQueries {

    private final UnitOfWork unitOfWork;
    private final ResponseReader responses;

    public RespondentQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        this.unitOfWork = unitOfWork;
        this.responses = responses;
    }

    public RespondentView.Page page(UUID surveyId, RespondentView.State state, String source, int offset, int limit) {
        return unitOfWork.transactional(() -> {
            List<RespondentView> rows = new ArrayList<>();
            for (VisitRow visit : responses.visits(surveyId, null, null)) {
                if (source != null && !source.isBlank() && !source.equals(visit.source())) {
                    continue;
                }
                if (state != null && !matches(visit, state)) {
                    continue;
                }
                String identity = visit.responseId() == null
                        ? null
                        : responses.response(visit.responseId())
                                .map(ResponseReader.ResponseRow::respondentIdentity)
                                .orElse(null);
                rows.add(new RespondentView(
                        identity, null, visit.source(), visit.state(), visit.lastActivityAt(), visit.responseId()));
            }
            long total = rows.size();
            List<RespondentView> window = rows.stream().skip(offset).limit(limit).toList();
            return new RespondentView.Page(window, total);
        });
    }

    /**
     * Whether a visit matches the state a Tenant narrowed by. A visit still open counts as started once answering began,
     * because that is the list a resend is aimed at: someone who answered and did not finish.
     */
    private static boolean matches(VisitRow visit, RespondentView.State state) {
        String visitState = visit.state() == null ? "" : visit.state().toUpperCase(Locale.ROOT);
        return switch (state) {
            case COMPLETED -> visitState.equals("COMPLETED");
            case ABANDONED -> visitState.equals("ABANDONED");
            case STARTED -> visitState.equals("STARTED") || visitState.equals("OPENED");
        };
    }
}
