package dev.ngb.survey.collection.application.visit.service;

import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;

/**
 * The answer to one link opening: what the browser holds and what it may show next.
 *
 * @param visit the visit this opening produced, or the one a reopened personal link returned to
 * @param visitToken the token the browser presents from here on; null when a personal link resumed an existing visit,
 *     because the browser already holds one
 * @param flow the version's logic over no answers yet, so a client knows which pages and questions to expect
 */
public record OpenedLink(SurveyLink link, Visit visit, String visitToken, FlowResult flow) {

    /** A resumed opening, with the visit already present and no new token to hand out. */
    public OpenedLink(SurveyLink link, Visit visit, String visitToken) {
        this(link, visit, visitToken, null);
    }

    /** The same opening with the version's initial flow attached. */
    public OpenedLink with(FlowResult flow) {
        return new OpenedLink(link, visit, visitToken, flow);
    }
}
