package dev.ngb.survey.authoring.adapter.document.web;

import dev.ngb.survey.authoring.api.document.ValidationError;
import java.util.List;

/**
 * The wire shape of a validation report, shared by the draft endpoints and the error handler so every {@code 422}
 * answers the same body whatever raised it.
 */
public record ValidationResult(List<Problem> errors) {

    public static ValidationResult of(List<ValidationError> errors) {
        return new ValidationResult(errors.stream().map(Problem::of).toList());
    }

    public record Problem(String pointer, String code, String message) {

        public static Problem of(ValidationError error) {
            return new Problem(error.pointer(), error.code().wireName(), error.message());
        }
    }
}
