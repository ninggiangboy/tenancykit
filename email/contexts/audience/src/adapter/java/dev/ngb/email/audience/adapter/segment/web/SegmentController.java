package dev.ngb.email.audience.adapter.segment.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.audience.application.segment.command.CreateSegmentUseCase;
import dev.ngb.email.audience.application.segment.command.DeleteSegmentUseCase;
import dev.ngb.email.audience.application.segment.command.PreviewSegmentUseCase;
import dev.ngb.email.audience.application.segment.command.UpdateSegmentUseCase;
import dev.ngb.email.audience.application.segment.query.SegmentQueries;
import dev.ngb.email.audience.application.segment.query.SegmentView;
import dev.ngb.email.audience.domain.segment.Segment;
import dev.ngb.email.shared.EmailPermissions;
import java.time.Instant;
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

/** Saved filters over receiver attributes. The filter language is closed; the operator set is the contract. */
@RestController
@RequestMapping("/api/v1/segments")
class SegmentController {

    private final CreateSegmentUseCase create;
    private final UpdateSegmentUseCase update;
    private final DeleteSegmentUseCase delete;
    private final PreviewSegmentUseCase preview;
    private final SegmentQueries queries;

    SegmentController(
            CreateSegmentUseCase create,
            UpdateSegmentUseCase update,
            DeleteSegmentUseCase delete,
            PreviewSegmentUseCase preview,
            SegmentQueries queries) {
        this.create = create;
        this.update = update;
        this.delete = delete;
        this.preview = preview;
        this.queries = queries;
    }

    @GetMapping
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    List<SegmentResponse> list(
            @RequestParam(required = false) UUID after, @RequestParam(required = false) Integer limit) {
        return queries.list(after, limit).stream().map(SegmentResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    SegmentResponse create(@RequestBody SegmentRequest request) {
        return SegmentResponse.of(create.execute(request.name(), request.filter()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    ResponseEntity<SegmentResponse> get(@PathVariable UUID id) {
        SegmentView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(SegmentResponse.of(view));
    }

    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    ResponseEntity<SegmentResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody SegmentRequest request) {
        Segment segment = update.execute(
                id, request.name(), request.filter(), ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(segment.version()))
                .body(SegmentResponse.of(segment));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(EmailPermissions.RECEIVER_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    @PostMapping("/{id}/preview")
    @RequiresPermission(EmailPermissions.RECEIVER_READ)
    PreviewSegmentUseCase.Preview preview(@PathVariable UUID id) {
        return preview.execute(id);
    }

    record SegmentRequest(String name, Map<String, Object> filter) {}

    record SegmentResponse(
            UUID id, String name, Map<String, Object> filter, long version, Instant createdAt, Instant updatedAt) {

        static SegmentResponse of(Segment segment) {
            return new SegmentResponse(
                    segment.id(), segment.name(), segment.filter(), segment.version(),
                    segment.createdAt(), segment.updatedAt());
        }

        static SegmentResponse of(SegmentView view) {
            return new SegmentResponse(
                    view.id(), view.name(), view.filter(), view.version(), view.createdAt(), view.updatedAt());
        }
    }
}
