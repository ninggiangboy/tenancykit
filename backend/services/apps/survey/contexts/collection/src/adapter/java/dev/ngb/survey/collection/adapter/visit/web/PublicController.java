package dev.ngb.survey.collection.adapter.visit.web;

import dev.ngb.survey.authoring.api.flow.SurveyFlow;
import dev.ngb.survey.collection.application.guard.service.Honeypot;
import dev.ngb.survey.collection.application.visit.exception.AlreadyCompletedException;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.application.visit.command.RecordPageViewUseCase;
import dev.ngb.survey.collection.application.visit.query.VisitQueries;
import dev.ngb.survey.collection.application.visit.query.VisitView;
import dev.ngb.survey.collection.application.visit.service.OpenedLink;
import dev.ngb.survey.collection.domain.link.LinkTokens;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.collection.domain.visit.VisitToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import dev.ngb.core.security.PublicEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The public fill surface a respondent's browser calls with no suite token.
 *
 * <p>The credential is the link's signed token on the way in and the visit's opaque token afterwards. Every failure
 * answers the same {@code 404} body — a malformed token, an unknown id, a revoked link, an expired one, another
 * tenant's — because a difference between them would let a caller probe for live links.
 *
 * <p>The rate limit and the body limit are applied by a filter in front of this controller, because they must cost
 * less than parsing a request and the stock Caddy image has no rate-limit directive.
 */
@Tag(
        name = "Public fill surface",
        description =
                "The unauthenticated endpoints a respondent's browser uses to open a link and read the survey.")
@RestController
@RequestMapping("/p")
class PublicController {

    private final OpenLinkUseCase openLink;
    private final VisitQueries visits;
    private final RecordPageViewUseCase pageViews;
    private final SurveyFlow flow;
    private final LinkTokens.Keys linkKeys;

    PublicController(
            OpenLinkUseCase openLink,
            VisitQueries visits,
            RecordPageViewUseCase pageViews,
            SurveyFlow flow,
            LinkTokens.Keys linkKeys) {
        this.openLink = openLink;
        this.visits = visits;
        this.pageViews = pageViews;
        this.flow = flow;
        this.linkKeys = linkKeys;
    }

    @Operation(
            summary = "Open a survey link",
            description =
                    "Starts a visit from a link's signed `token`, recording the referrer, user agent and query's source "
                    + "facts, and answers the visit token the browser uses from then on. Every failure answers the same "
                    + "`404` body, whether the token is malformed, unknown, revoked, expired or another tenant's, so a "
                    + "caller cannot probe for live links. Public: no suite token is needed.")
    @PublicEndpoint
    @PostMapping("/l/{token}/open")
    OpenedResponse open(
            @PathVariable String token,
            @RequestHeader(value = "Referer", required = false) String referrer,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestParam Map<String, String> query,
            HttpServletRequest request) {
        UUID linkId = LinkTokens.verify(token, linkKeys).orElseThrow(PublicController::notFound);
        try {
            OpenedLink opened = openLink
                    .execute(linkId, new OpenLinkUseCase.ArrivalRequest(
                            referrer, query, userAgent, request.getRemoteAddr()))
                    .orElseThrow(PublicController::notFound);
            Visit visit = opened.visit();
            var version = visits.versionOf(new VisitView(
                    visit.id(), visit.tenantId(), visit.surveyId(), visit.linkId(), visit.versionNo(),
                    visit.source(), visit.state().name(), visit.responseId(), visit.openedAt(),
                    visit.lastActivityAt()));
            return new OpenedResponse(
                    opened.visitToken(),
                    visit.surveyId(),
                    version.versionNo(),
                    Honeypot.nameFor(visit.id()),
                    flow.run(version.document(), Map.of()));
        } catch (AlreadyCompletedException completed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_completed", completed);
        }
    }

    @Operation(
            summary = "Get the survey a visit fills",
            description =
                    "Returns the published version the visit pinned, as `locale` renders it, with the decoy field a bot "
                    + "would fill. Public: the visit token in the path is the credential.")
    @PublicEndpoint
    @GetMapping("/v/{visitToken}/schema")
    SchemaResponse schema(@PathVariable String visitToken, @RequestParam(required = false) String locale) {
        VisitView visit = visit(visitToken);
        var version = visits.versionOf(visit);
        return new SchemaResponse(
                version.versionNo(), locale, version.document(), Honeypot.nameFor(visit.id()));
    }

    @Operation(
            summary = "Record a page view",
            description =
                    "Records that the respondent saw page `pageId`, which feeds the drop-off funnel. Answers no body. "
                    + "Public: the visit token in the path is the credential.")
    @PublicEndpoint
    @PostMapping("/v/{visitToken}/pages/{pageId}/view")
    void pageViewed(@PathVariable String visitToken, @PathVariable UUID pageId) {
        VisitView visit = visit(visitToken);
        if (!pageViews.execute(visit.id(), pageId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "page_view_limit_reached");
        }
    }

    /** Resolves a visit token, or answers the not-found every invalid credential answers. */
    private VisitView visit(String visitToken) {
        byte[] hash = VisitToken.hashOfPresented(visitToken).orElseThrow(PublicController::notFound);
        return visits.byToken(hash).orElseThrow(PublicController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No link exists here.");
    }

    /**
     * The answer to one opening: the token to present next, the version the visit pinned, the decoy field's name and the
     * flow over no answers yet, so a client knows which pages to expect.
     *
     * @param visitToken null when a personal link resumed the visit its browser already holds
     */
    record OpenedResponse(
            String visitToken,
            UUID surveyId,
            int versionNo,
            String honeypot,
            dev.ngb.survey.authoring.api.flow.FlowResult flow) {}

    /** The version for one locale, which is what a respondent's browser renders. */
    record SchemaResponse(int versionNo, String locale, Map<String, Object> document, String honeypot) {}
}
