package dev.ngb.mailroom.content.adapter.template.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.content.application.render.port.RenderedParts;
import dev.ngb.mailroom.content.application.template.command.CreateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.DeleteTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.PreviewTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.UpdateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.query.TemplateListing;
import dev.ngb.mailroom.content.application.template.query.TemplateQueries;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionListing;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionView;
import dev.ngb.mailroom.content.application.template.query.TemplateView;
import dev.ngb.mailroom.content.domain.template.Template;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's templates. `key` is immutable once created; a `PUT` appends a revision and moves the pointer, guarded by
 * `If-Match` so a concurrent edit is refused rather than silently overwriting the other one.
 */
@Tag(
        name = "Email templates",
        description =
                "A tenant's email templates. Every edit appends an immutable revision, so recorded emails and "
                + "campaigns never change.")
@RestController
@RequestMapping("/api/v1/templates")
class TemplateController {

    private final CreateTemplateUseCase create;
    private final UpdateTemplateUseCase update;
    private final DeleteTemplateUseCase delete;
    private final PreviewTemplateUseCase preview;
    private final TemplateQueries queries;

    TemplateController(
            CreateTemplateUseCase create,
            UpdateTemplateUseCase update,
            DeleteTemplateUseCase delete,
            PreviewTemplateUseCase preview,
            TemplateQueries queries) {
        this.create = create;
        this.update = update;
        this.delete = delete;
        this.preview = preview;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link TemplateListing}. */
    @Operation(
            summary = "List email templates",
            description =
                    "Pages through the tenant's templates, with the list-query contract's filtering, sorting and paging. "
                    + "Requires `mailroom:template:read`.")
    @GetMapping
    @Listing(TemplateListing.class)
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    Page<TemplateResponse> list(ListQuery query) {
        return queries.list(query).map(TemplateResponse::ofView);
    }

    @Operation(
            summary = "Get an email template",
            description =
                    "Returns one template at its current revision; its version is also the response's `ETag`. An unknown "
                    + "id answers `404`. Requires `mailroom:template:read`.")
    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    ResponseEntity<TemplateResponse> get(@PathVariable UUID id) {
        TemplateResponse view = TemplateResponse.ofView(queries.get(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(view);
    }

    @Operation(
            summary = "Create an email template",
            description =
                    "Creates a template with a `key`, a name, a subject and an HTML and text body, as its first immutable "
                    + "revision. The `key` can never change afterwards. A hostile or oversize body is refused before it is "
                    + "stored. Answers `201`. Requires `mailroom:template:manage`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(MailroomPermissions.TEMPLATE_MANAGE)
    TemplateResponse create(@RequestBody TemplateRequest request) {
        Template template = create.execute(new CreateTemplateUseCase.Command(
                request.key(), request.name(), request.subject(), request.html(), request.text()));
        return TemplateResponse.of(template);
    }

    @Operation(
            summary = "Edit an email template",
            description =
                    "Appends a new immutable revision with the name, subject and bodies given and moves the template's "
                    + "pointer to it. A revision an email or campaign already recorded is never rewritten. Pass the current "
                    + "`ETag` in `If-Match`; a stale version is refused. Requires `mailroom:template:manage`.")
    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.TEMPLATE_MANAGE)
    ResponseEntity<TemplateResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody TemplateRequest request) {
        Template template = update.execute(
                id,
                new UpdateTemplateUseCase.Change(
                        request.name(), request.subject(), request.html(), request.text()),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(template.version()))
                .body(TemplateResponse.of(template));
    }

    @Operation(
            summary = "Delete an email template",
            description =
                    "Removes the template. Its revisions are kept, so a campaign that pinned one still renders. Pass the "
                    + "current `ETag` in `If-Match`; a stale version is refused. Answers `204`. Requires "
                    + "`mailroom:template:manage`.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.TEMPLATE_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    /** The template is fixed by the path; filter, sort and paging follow the list-query contract. */
    @Operation(
            summary = "List a template's revisions",
            description =
                    "Pages through the immutable revisions of the template named by the path, with the list-query "
                    + "contract's filtering, sorting and paging. Requires `mailroom:template:read`.")
    @GetMapping("/{id}/revisions")
    @Listing(TemplateRevisionListing.class)
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    Page<TemplateRevisionView> revisions(@PathVariable UUID id, ListQuery query) {
        return queries.revisions(id, query);
    }

    @Operation(
            summary = "Preview a rendered template",
            description =
                    "Renders the template's current revision with the `variables` given, under the same limits a real "
                    + "send obeys, and returns the result without sending anything. Requires `mailroom:template:read`.")
    @PostMapping("/{id}/preview")
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    RenderedResponse preview(@PathVariable UUID id, @RequestBody(required = false) PreviewRequest request) {
        Map<String, Object> variables = request == null || request.variables() == null ? Map.of() : request.variables();
        RenderedParts parts = preview.execute(id, variables);
        return new RenderedResponse(parts.subject(), parts.html(), parts.text());
    }

    record TemplateRequest(String key, String name, String subject, String html, String text) {}

    record PreviewRequest(Map<String, Object> variables) {}

    record RenderedResponse(String subject, String html, String text) {}

    record TemplateResponse(UUID id, String key, String name, int revision, long version) {

        static TemplateResponse of(Template template) {
            return new TemplateResponse(
                    template.id(), template.key().value(), template.name(), template.currentRevision(), template.version());
        }

        static TemplateResponse ofView(TemplateView view) {
            return new TemplateResponse(
                    view.id(), view.key(), view.name(), view.currentRevision(), view.version());
        }
    }
}
