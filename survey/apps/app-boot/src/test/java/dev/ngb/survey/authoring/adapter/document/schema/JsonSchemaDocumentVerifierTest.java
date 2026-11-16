package dev.ngb.survey.authoring.adapter.document.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.support.Documents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The verifier reads the real packaged contract, not a copy: the version schema plus the expression schema it
 * references by relative URI. A failure is reported at the member the builder sent, with one of the closed codes.
 */
class JsonSchemaDocumentVerifierTest {

    private final JsonSchemaDocumentVerifier verifier =
            new JsonSchemaDocumentVerifier(JsonMapper.builder().build());

    @Test
    void theFixtureDocumentHasTheVersionsShape() {
        assertThat(verifier.structuralProblems(Documents.valid())).isEmpty();
    }

    @Test
    void anUnknownQuestionTypeIsReportedAtTheType() {
        Map<String, Object> document = Documents.valid();
        Documents.question(document, 0, 1).put("type", "smell");

        assertThat(verifier.structuralProblems(document))
                .anySatisfy(error -> {
                    assertThat(error.pointer()).isEqualTo("/pages/0/questions/1/type");
                    assertThat(error.code()).isEqualTo(ValidationCode.UNKNOWN_TYPE);
                });
    }

    @Test
    void aMissingMemberIsRequired() {
        Map<String, Object> document = Documents.valid();
        document.remove("settings");

        assertThat(verifier.structuralProblems(document))
                .anySatisfy(error -> {
                    assertThat(error.pointer()).isEqualTo("/settings");
                    assertThat(error.code()).isEqualTo(ValidationCode.REQUIRED);
                });
    }

    @Test
    void tooManyPagesExceedTheLimit() {
        Map<String, Object> document = Documents.valid();
        @SuppressWarnings("unchecked")
        List<Object> pages = (List<Object>) document.get("pages");
        for (int index = 0; index < 201; index++) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", "77777777-7777-7777-8777-" + String.format("%012d", index));
            pages.add(page);
        }

        assertThat(verifier.structuralProblems(document))
                .anySatisfy(error -> {
                    assertThat(error.pointer()).isEqualTo("/pages");
                    assertThat(error.code()).isEqualTo(ValidationCode.LIMIT_EXCEEDED);
                });
    }

    @Test
    void anIdThatIsNotAUuidIsRejected() {
        Map<String, Object> document = Documents.valid();
        Documents.page(document, 0).put("id", "not-a-uuid");

        assertThat(verifier.structuralProblems(document))
                .as("`format: uuid` is asserted, so a stored id is always one the server can use")
                .anySatisfy(error -> assertThat(error.pointer()).isEqualTo("/pages/0/id"));
    }

    @Test
    void aChoiceNeedsTwoOptions() {
        Map<String, Object> document = Documents.valid();
        Map<String, Object> question = Documents.question(document, 0, 1);
        @SuppressWarnings("unchecked")
        List<Object> options = (List<Object>) question.get("options");
        options.removeLast();

        assertThat(verifier.structuralProblems(document)).isNotEmpty();
    }

    /** The relative reference to the expression schema is resolved, so an expression outside the closed set is caught. */
    @Test
    void anExpressionOutsideTheClosedSetIsRejected() {
        Map<String, Object> document = Documents.valid();
        Map<String, Object> computed = new LinkedHashMap<>();
        computed.put("name", "score");
        computed.put("expr", Map.of("eval_java", List.of("Runtime.getRuntime()")));
        List<Object> variables = new ArrayList<>();
        variables.add(computed);
        document.put("computed", variables);

        List<ValidationError> errors = verifier.structuralProblems(document);

        assertThat(errors)
                .anySatisfy(error -> assertThat(error.pointer()).startsWith("/computed/0/expr"));
    }
}
