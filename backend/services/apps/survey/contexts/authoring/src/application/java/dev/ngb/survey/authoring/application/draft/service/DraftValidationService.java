package dev.ngb.survey.authoring.application.draft.service;

import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.port.DocumentVerifier;
import dev.ngb.survey.authoring.domain.draft.DraftRules;
import dev.ngb.survey.authoring.domain.flow.FlowRunner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The whole answer to "may this document become a version": the schema's shape check, the domain's semantic rules and
 * the expression budgets, reported together and in a stable order.
 *
 * <p>All three run even when another fails, so a builder sees every problem in one round rather than fixing them one
 * request at a time. The expression check is the hook the plan left open in the previous phase: it parses every
 * expression, resolves every reference and counts every node, which is what a schema cannot do.
 */
public class DraftValidationService {

    private final DocumentVerifier verifier;

    public DraftValidationService(DocumentVerifier verifier) {
        this.verifier = verifier;
    }

    public List<ValidationError> check(Map<String, Object> document) {
        List<ValidationError> errors = new ArrayList<>(verifier.structuralProblems(document));
        errors.addAll(DraftRules.problems(document));
        errors.addAll(FlowRunner.problems(document));
        List<ValidationError> distinct = errors.stream().distinct().toList();
        List<ValidationError> sorted = new ArrayList<>(distinct);
        sorted.sort(Comparator.comparing(ValidationError::pointer)
                .thenComparing(error -> error.code().wireName())
                .thenComparing(ValidationError::message));
        return List.copyOf(sorted);
    }
}
