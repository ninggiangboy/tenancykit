package dev.ngb.survey.insight.adapter.export.web;

import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.insight.application.export.command.RequestExportUseCase;
import dev.ngb.survey.insight.application.export.query.ExportQueries;
import dev.ngb.survey.insight.domain.export.Export;
import dev.ngb.survey.shared.SurveyPermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exports a Tenant asks for and reads back.
 *
 * <p>A request queues the work and answers its state; the worker produces the CSV, and a finished export carries a
 * presigned download that expires in fifteen minutes.
 */
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

    @GetMapping("/surveys/{id}/exports")
    @RequiresPermission(SurveyPermissions.EXPORT_MANAGE)
    List<ExportQueries.ExportView> list(
            @PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        return exports.ofSurvey(id, Math.min(Math.max(1, limit), 100));
    }

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
