package dev.ngb.survey.insight.adapter.export.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.insight.application.export.command.RequestExportUseCase;
import dev.ngb.survey.insight.application.export.query.ExportListing;
import dev.ngb.survey.insight.application.export.query.ExportQueries;
import dev.ngb.survey.insight.domain.export.Export;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
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
 * The exports a Tenant asks for and reads back.
 *
 * <p>A request queues the work and answers its state; the worker produces the CSV, and a finished export carries a
 * presigned download that expires in fifteen minutes.
 */
@Tag(
        name = "Survey exports",
        description =
                "Queued CSV exports of responses and reading their state.")
@RestController
@RequestMapping("/api/v1")
class ExportController {

    private final RequestExportUseCase request;
    private final ExportQueries exports;
    private final CurrentActor currentActor;

    ExportController(RequestExportUseCase request, ExportQueries exports, CurrentActor currentActor) {
        this.request = request;
        this.exports = exports;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Request a CSV export",
            description =
                    "Queues an export of the survey's responses narrowed by `filter` and answers its state at once; a "
                    + "worker produces the CSV. Poll the export until it finishes, then use its download link, which "
                    + "expires in fifteen minutes. Answers `201`. Requires `survey:export:manage`.")
    @PostMapping("/surveys/{id}/exports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(SurveyPermissions.EXPORT_MANAGE)
    ExportQueries.ExportView create(@PathVariable UUID id, @RequestBody ExportRequest body) {
        Export export = request.execute(
                id,
                body.filter() == null ? Map.of() : body.filter(),
                currentActor.get().auditName());
        return exports.get(export.id());
    }

    @Operation(
            summary = "List a survey's exports",
            description =
                    "Pages through the exports requested for the survey named by the path, with the list-query contract's "
                    + "filtering, sorting and paging. Requires `survey:export:manage`.")
    @GetMapping("/surveys/{id}/exports")
    @Listing(ExportListing.class)
    @RequiresPermission(SurveyPermissions.EXPORT_MANAGE)
    Page<ExportQueries.ExportView> list(@PathVariable UUID id, ListQuery query) {
        return exports.ofSurvey(id, query);
    }

    @Operation(
            summary = "Get an export",
            description =
                    "Returns one export with its state; a finished export carries a presigned download that expires in "
                    + "fifteen minutes. An unknown id answers `404`. Requires `survey:export:manage`.")
    @GetMapping("/exports/{exportId}")
    @RequiresPermission(SurveyPermissions.EXPORT_MANAGE)
    ExportQueries.ExportView get(@PathVariable UUID exportId) {
        return exports.get(exportId);
    }

    /**
     * A body that asks for an export.
     *
     * @param filter the version, window, source and identity the file is narrowed by, as JSON
     */
    record ExportRequest(Map<String, Object> filter) {}
}
