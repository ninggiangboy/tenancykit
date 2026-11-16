package dev.ngb.survey.insight.adapter.response.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.collection.api.response.ResponseDeleter;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.insight.application.response.query.ResponseQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The responses a Tenant reads and deletes.
 *
 * <p>A delete goes through {@code collection}'s own use case, because that context owns the rows and the upload objects
 * and {@code insight} writes nothing but its export ledger. Everything else here is a read over collection's api, so a
 * deleted response disappears from every report without a second copy to keep in step.
 */
@RestController
@RequestMapping("/api/v1")
class ResponseController {

    private final ResponseQueries responses;
    private final ResponseDeleter deleter;

    ResponseController(ResponseQueries responses, ResponseDeleter deleter) {
        this.responses = responses;
        this.deleter = deleter;
    }

    @GetMapping("/surveys/{id}/responses")
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    ResponsePage list(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String external_ref,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        var page = responses.page(
                id,
                ResponseQueries.filterOf(version, from, to, source, external_ref),
                Math.max(0, offset),
                Math.min(Math.max(1, limit), 200));
        return new ResponsePage(page.rows().stream().map(ResponseRow::of).toList(), page.total());
    }

    @GetMapping("/responses/{responseId}")
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    ResponseRow get(@PathVariable UUID responseId) {
        return ResponseRow.of(responses.get(responseId));
    }

    @DeleteMapping("/responses/{responseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(SurveyPermissions.RESPONSE_DELETE)
    void delete(@PathVariable UUID responseId) {
        deleter.delete(responseId);
    }

    /** A page of responses and the total the caller should say it saw. */
    record ResponsePage(List<ResponseRow> responses, long total) {}

    /** One response as the API returns it: the record and the server's own variables and score. */
    record ResponseRow(
            UUID id,
            UUID versionId,
            String status,
            String locale,
            Map<String, Object> answers,
            Map<String, Object> vars,
            BigDecimal score,
            String respondentIdentity,
            String externalRef,
            String source,
            Instant startedAt,
            Instant submittedAt) {

        static ResponseRow of(ResponseReader.ResponseRow row) {
            return new ResponseRow(
                    row.id(),
                    row.versionId(),
                    row.status(),
                    row.locale(),
                    row.answers(),
                    row.vars(),
                    row.score(),
                    row.respondentIdentity(),
                    row.externalRef(),
                    row.source(),
                    row.startedAt(),
                    row.submittedAt());
        }
    }
}
