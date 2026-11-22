package dev.ngb.survey.insight.adapter.response.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.collection.api.response.ResponseDeleter;
import dev.ngb.survey.collection.api.response.ResponseListing;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.insight.application.response.query.ResponseQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The responses a Tenant reads and deletes.
 *
 * <p>A delete goes through {@code collection}'s own use case, because that context owns the rows and the upload objects
 * and {@code insight} writes nothing but its export ledger. Everything else here is a read over collection's api, so a
 * deleted response disappears from every report without a second copy to keep in step.
 */
@Tag(
        name = "Survey responses",
        description =
                "Reading and deleting completed responses.")
@RestController
@RequestMapping("/api/v1")
class ResponseController {

    private final ResponseQueries responses;
    private final ResponseDeleter deleter;

    ResponseController(ResponseQueries responses, ResponseDeleter deleter) {
        this.responses = responses;
        this.deleter = deleter;
    }

    @Operation(
            summary = "List a survey's responses",
            description =
                    "Pages through the survey's completed responses, with the list-query contract's filtering, sorting "
                    + "and paging. Requires `survey:response:read`.")
    @GetMapping("/surveys/{id}/responses")
    @Listing(ResponseListing.class)
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    Page<ResponseRow> list(@PathVariable UUID id, ListQuery query) {
        return responses.page(id, query).map(ResponseRow::of);
    }

    @Operation(
            summary = "Get a response",
            description =
                    "Returns one response with its answers as submitted, and the variables and score the server computed "
                    + "at submit. It carries no address, token or user agent. An unknown id answers `404`. Requires "
                    + "`survey:response:read`.")
    @GetMapping("/responses/{responseId}")
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    ResponseRow get(@PathVariable UUID responseId) {
        return ResponseRow.of(responses.get(responseId));
    }

    @Operation(
            summary = "Delete a response",
            description =
                    "Deletes the response and everything that belongs to it, including uploaded files, so it disappears "
                    + "from every report. The visit's source and device facts survive, and the survey's submission counter "
                    + "is not decremented. Answers `204`. Requires `survey:response:delete`.")
    @DeleteMapping("/responses/{responseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(SurveyPermissions.RESPONSE_DELETE)
    void delete(@PathVariable UUID responseId) {
        deleter.delete(responseId);
    }

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
