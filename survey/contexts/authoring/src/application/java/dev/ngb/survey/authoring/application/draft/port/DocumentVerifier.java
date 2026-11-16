package dev.ngb.survey.authoring.application.draft.port;

import dev.ngb.survey.authoring.api.document.ValidationError;
import java.util.List;
import java.util.Map;

/**
 * The structural check: the document's shape against {@code contracts/survey/version.schema.json}. Whoever implements
 * it owns the schema file, not the rules; the semantic rules live in the domain and run on a document that passed here.
 */
public interface DocumentVerifier {

    /** @return every structural problem, or an empty list when the document has the version's shape */
    List<ValidationError> structuralProblems(Map<String, Object> document);
}
