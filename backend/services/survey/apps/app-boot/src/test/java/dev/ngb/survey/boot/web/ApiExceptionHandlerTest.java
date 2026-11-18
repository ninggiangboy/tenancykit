package dev.ngb.survey.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.survey.authoring.adapter.document.web.ValidationResult;
import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.shared.ConditionalRequests;
import dev.ngb.survey.shared.PreconditionRequiredException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The two answers a builder depends on: the full validation report as {@code 422}, and a missing tag as {@code 428}. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aDocumentThatWouldNotPublishIsA422CarryingEveryPointer() {
        DraftInvalidException failure = new DraftInvalidException(List.of(
                new ValidationError("/translations/en/survey.title", ValidationCode.TRANSLATION_MISSING, "missing"),
                new ValidationError("/pages", ValidationCode.LIMIT_EXCEEDED, "too many")));

        ResponseEntity<ValidationResult> response = handler.invalidDocument(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).isEqualTo(new ValidationResult(List.of(
                new ValidationResult.Problem("/translations/en/survey.title", "translation_missing", "missing"),
                new ValidationResult.Problem("/pages", "limit_exceeded", "too many"))));
    }

    @Test
    void aMissingTagIsA428NotA412() {
        assertThat(handler.preconditionRequired(new PreconditionRequiredException()).getStatus())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED.value());
    }

    @Test
    void aConditionalEndpointRefusesToRunWithoutATag() {
        assertThatThrownBy(() -> ConditionalRequests.requireIfMatch(null))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ConditionalRequests.requireIfMatch("  "))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThat(ConditionalRequests.requireIfMatch("*")).isNotNull();
        assertThat(ConditionalRequests.requireIfMatch("\"3\"")).isNotNull();
    }
}
