package dev.ngb.mailroom.audience.adapter.segment.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.audience.application.segment.command.CreateSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.DeleteSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.PreviewSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.UpdateSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.query.SegmentListing;
import dev.ngb.mailroom.audience.application.segment.query.SegmentQueries;
import dev.ngb.mailroom.audience.application.segment.query.SegmentView;
import dev.ngb.mailroom.audience.domain.segment.Segment;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Segments",
        description =
                "Saved filters over receiver attributes, with a preview of the audience they match.")
@RestController
@RequestMapping("/segments")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
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

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link SegmentListing}. */
    @Operation(
            summary = "List segments",
            description =
                    "Pages through the tenant's saved segments, with the list-query contract's filtering, sorting and "
                    + "paging.")
    @GetMapping
    @Listing(SegmentListing.class)
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    Page<SegmentResponse> list(ListQuery query) {
        return queries.list(query).map(SegmentResponse::of);
    }

    @Operation(
            summary = "Create a segment",
            description =
                    "Saves a named filter over receiver attributes. The filter language is closed and the filter is "
                    + "validated before it is stored. Answers `201`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    SegmentResponse create(@RequestBody SegmentRequest request) {
        return SegmentResponse.of(create.execute(request.name(), request.filter()));
    }

    @Operation(
            summary = "Get a segment",
            description =
                    "Returns one segment with its filter; its version is also the response's `ETag`. An unknown id "
                    + "answers `404`.")
    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
    ResponseEntity<SegmentResponse> get(@PathVariable UUID id) {
        SegmentView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(SegmentResponse.of(view));
    }

    @Operation(
            summary = "Replace a segment",
            description =
                    "Replaces the segment's name and filter; the new filter is validated first. Pass the current `ETag` "
                    + "in `If-Match`; a stale version is refused.")
    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
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

    @Operation(
            summary = "Delete a segment",
            description =
                    "Deletes the saved segment. A campaign that already snapshotted it keeps its recipients. Pass the "
                    + "current `ETag` in `If-Match`; a stale version is refused. Answers `204`. Requires "
                    + "`mailroom:receiver:manage`.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(MailroomPermissions.RECEIVER_MANAGE)
    void delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        delete.execute(id, ExpectedVersion.fromIfMatch(ifMatch));
    }

    @Operation(
            summary = "Preview a segment's audience",
            description =
                    "Answers how many receivers the segment matches now, with a sample, without loading the whole "
                    + "audience.")
    @PostMapping("/{id}/preview")
    @RequiresPermission(MailroomPermissions.RECEIVER_READ)
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
