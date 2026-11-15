package dev.ngb.mailroom.content.adapter.template.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.content.application.render.port.RenderedParts;
import dev.ngb.mailroom.content.application.template.command.CreateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.DeleteTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.PreviewTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.UpdateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.query.TemplateQueries;
import dev.ngb.mailroom.content.application.template.query.TemplateRevisionView;
import dev.ngb.mailroom.content.application.template.query.TemplateView;
import dev.ngb.mailroom.content.domain.template.Template;
import dev.ngb.mailroom.shared.MailroomPermissions;
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

    @GetMapping
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    List<TemplateResponse> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) UUID after) {
        return queries.list(limit, after).stream().map(TemplateResponse::ofView).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    ResponseEntity<TemplateResponse> get(@PathVariable UUID id) {
        TemplateResponse view = TemplateResponse.ofView(queries.get(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(view);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(MailroomPermissions.TEMPLATE_MANAGE)
    TemplateResponse create(@RequestBody TemplateRequest request) {
        Template template = create.execute(new CreateTemplateUseCase.Command(
                request.key(), request.name(), request.subject(), request.html(), request.text()));
        return TemplateResponse.of(template);
    }

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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.TEMPLATE_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    @GetMapping("/{id}/revisions")
    @RequiresPermission(MailroomPermissions.TEMPLATE_READ)
    List<TemplateRevisionView> revisions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int after,
            @RequestParam(required = false) Integer limit) {
        return queries.revisions(id, after, limit);
    }

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
