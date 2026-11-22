package dev.ngb.survey.collection.adapter.link.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.collection.application.link.command.CreatePersonalLinksUseCase;
import dev.ngb.survey.collection.application.link.command.CreatePublicLinkUseCase;
import dev.ngb.survey.collection.application.link.command.RevokeLinkUseCase;
import dev.ngb.survey.collection.application.link.query.LinkListing;
import dev.ngb.survey.collection.application.link.query.LinkQueries;
import dev.ngb.survey.collection.application.link.query.LinkView;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Tenant's links: one public link per source, a bulk of personal links, and revocation.
 *
 * <p>Every answer carries the URL rebuilt from the link id, so a Tenant reads a link back at any time rather than
 * storing what it was handed.
 */
@Tag(
        name = "Survey links",
        description =
                "Public and personal links that open a survey, and revoking them.")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class LinkController {

    private final CreatePublicLinkUseCase createPublic;
    private final CreatePersonalLinksUseCase createPersonal;
    private final RevokeLinkUseCase revoke;
    private final LinkQueries queries;

    LinkController(
            CreatePublicLinkUseCase createPublic,
            CreatePersonalLinksUseCase createPersonal,
            RevokeLinkUseCase revoke,
            LinkQueries queries) {
        this.createPublic = createPublic;
        this.createPersonal = createPersonal;
        this.revoke = revoke;
        this.queries = queries;
    }

    @Operation(
            summary = "Create a survey's public link",
            description =
                    "Issues the one public link for `source`. A second link for the same source is refused rather than "
                    + "merged, because two links would split one campaign's visits into rows a tenant cannot tell apart; a "
                    + "survey that is not open is refused too, both with `409`. The answer carries the URL to hand out. "
                    + "Answers `201`. ")
    @PostMapping("/surveys/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(SurveyPermissions.LINK_MANAGE)
    LinkResponse createPublic(@PathVariable UUID id, @RequestBody PublicLinkRequest request) {
        return LinkResponse.of(queries.withUrl(viewOf(createPublic.execute(id, request.source()))));
    }

    @Operation(
            summary = "Create personal links in bulk",
            description =
                    "Issues one personal link per recipient in `links`, each with its own `externalRef`, optional "
                    + "`source` and optional `expiresAt`; `source` is the default for entries that name none. It is how an "
                    + "imported mailroom segment becomes links. A duplicate `externalRef` in the request answers `422` before "
                    + "anything is written, and a survey that is not open answers `409`. Answers `201` with the links. ")
    @PostMapping("/surveys/{id}/links/personal")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(SurveyPermissions.LINK_MANAGE)
    List<LinkResponse> createPersonal(@PathVariable UUID id, @RequestBody PersonalLinksRequest request) {
        List<CreatePersonalLinksUseCase.Recipient> recipients = request.links() == null
                ? List.of()
                : request.links().stream()
                        .map(link -> new CreatePersonalLinksUseCase.Recipient(
                                link.externalRef(), link.source(), link.expiresAt()))
                        .toList();
        return createPersonal.execute(id, request.source(), recipients).stream()
                .map(link -> LinkResponse.of(queries.withUrl(viewOf(link))))
                .toList();
    }

    @Operation(
            summary = "List a survey's links",
            description =
                    "Pages through the links of the survey named by the path, newest first by default, with the "
                    + "list-query contract's filtering, sorting and paging. Each link carries its URL. Requires "
                    + "`survey:link:manage`.")
    @GetMapping("/surveys/{id}/links")
    @Listing(LinkListing.class)
    @RequiresPermission(SurveyPermissions.LINK_MANAGE)
    Page<LinkResponse> list(@PathVariable UUID id, ListQuery query) {
        return queries.list(id, query).map(LinkResponse::of);
    }

    @Operation(
            summary = "Get a link",
            description =
                    "Returns one link with its URL, which is rebuilt from the link id and so is always current. An "
                    + "unknown id answers `404`.")
    @GetMapping("/links/{linkId}")
    @RequiresPermission(SurveyPermissions.LINK_MANAGE)
    LinkResponse get(@PathVariable UUID linkId) {
        return LinkResponse.of(queries.get(linkId));
    }

    @Operation(
            summary = "Revoke a link",
            description =
                    "Revokes the link, the only way to stop a link already handed out from opening the survey. "
                    + "Idempotent: a link that is already revoked answers its current state.")
    @PostMapping("/links/{linkId}/revoke")
    @RequiresPermission(SurveyPermissions.LINK_MANAGE)
    LinkResponse revoke(@PathVariable UUID linkId) {
        return LinkResponse.of(queries.withUrl(viewOf(revoke.execute(linkId))));
    }

    private static LinkView viewOf(SurveyLink link) {
        return new LinkView(
                link.id(),
                link.surveyId(),
                link.kind().name(),
                link.source(),
                link.externalRef(),
                link.expiresAt(),
                link.status().name(),
                link.createdAt(),
                null);
    }

    /** A public link names its source; nothing else is accepted. */
    record PublicLinkRequest(String source) {}

    /**
     * A bulk of personal links: a default source applied to every recipient, and one entry per recipient.
     *
     * @param source the source every recipient inherits unless an entry names its own
     */
    record PersonalLinksRequest(String source, List<RecipientRequest> links) {}

    /** One recipient's reference, an optional source and an optional expiry. */
    record RecipientRequest(String externalRef, String source, Instant expiresAt) {}

    /** One link as the API returns it, with the URL derived from its id. */
    record LinkResponse(
            UUID id,
            UUID surveyId,
            String kind,
            String source,
            String externalRef,
            Instant expiresAt,
            String status,
            Instant createdAt,
            String url) {

        static LinkResponse of(LinkView view) {
            return new LinkResponse(
                    view.id(),
                    view.surveyId(),
                    view.kind(),
                    view.source(),
                    view.externalRef(),
                    view.expiresAt(),
                    view.status(),
                    view.createdAt(),
                    view.url());
        }
    }
}
