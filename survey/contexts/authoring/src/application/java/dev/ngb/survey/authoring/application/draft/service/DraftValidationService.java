package dev.ngb.survey.authoring.application.draft.service;

import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.port.DocumentVerifier;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The whole answer to "may this document become a version": the schema's shape check and the domain's semantic rules,
 * reported together and in a stable order.
 *
 * <p>Both run even when the other fails, so a builder sees every problem in one round rather than fixing them one
 * request at a time.
 */
public class DraftValidationService {

    private final DocumentVerifier verifier;

    public DraftValidationService(DocumentVerifier verifier) {
        this.verifier = verifier;
    }

    public List<ValidationError> check(Map<String, Object> document) {
        List<ValidationError> errors = new ArrayList<>(verifier.structuralProblems(document));
        errors.addAll(DraftRules.problems(document));
        errors.sort(Comparator.comparing(ValidationError::pointer)
                .thenComparing(error -> error.code().wireName())
                .thenComparing(ValidationError::message));
        return List.copyOf(errors);
    }
}
