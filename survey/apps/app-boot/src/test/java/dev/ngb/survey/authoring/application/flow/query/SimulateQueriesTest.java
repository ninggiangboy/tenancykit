package dev.ngb.survey.authoring.application.flow.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Simulate runs the same evaluator a submit will, against the stored document rather than against a copy: the draft
 * while it is still being authored, and a published version once it is frozen.
 *
 * <p>These prove the wiring the vectors cannot: the document a caller gets from storage is the one that evaluates, and
 * another tenant's survey answers exactly like one that does not exist.
 */
class SimulateQueriesTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired
    CreateSurveyUseCase createSurvey;
    @Autowired
    SaveDraftUseCase saveDraft;
    @Autowired
    PublishSurveyUseCase publish;
    @Autowired
    SimulateQueries simulations;
    @Autowired
    PreviewQueries previews;

    @Test
    void aDraftSimulationReportsThePageAndTheComputedVariable() {
        Prepared prepared = prepared();

        FlowResult result = inTenant(prepared.tenant(), () -> simulations.draft(prepared.survey().id(), Map.of(
                Documents.TEXT_QUESTION_ID, "yes")));

        assertThat(result.errors()).isEmpty();
        assertThat(result.path()).containsExactly(Documents.PAGE_ID);
        assertThat(result.visible()).containsEntry(Documents.TEXT_QUESTION_ID, true);
        assertThat(result.vars()).containsEntry("answered", true);
    }

    @Test
    void aPublishedVersionSimulatesTheLogicItWasPublishedWith() {
        Prepared prepared = prepared();
        inTenant(prepared.tenant(), () -> publish.execute(
                prepared.survey().id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));

        FlowResult result = inTenant(prepared.tenant(), () -> simulations.version(prepared.survey().id(), 1, Map.of()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.vars()).containsEntry("answered", false);
    }

    @Test
    void aDraftThatDoesNotCompileAnswersTheErrorRatherThanFailing() {
        Prepared prepared = unprepared();
        // The schema states shape, not budgets, so a draft over the node limit is storable and only the evaluator
        // reports it. That is exactly the gap the hook in the validation service closes.
        Map<String, Object> document = Documents.valid();
        document.put("computed", new ArrayList<>(List.of(overBudget())));
        inTenant(prepared.tenant(), () -> saveDraft.execute(
                prepared.survey().id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));

        FlowResult result = inTenant(prepared.tenant(), () -> simulations.draft(prepared.survey().id(), Map.of()));

        assertThat(result.path()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error.pointer()).isEqualTo("/computed/0/expr"));
    }

    @Test
    void aPreviewRendersTheLabelsAndThePlaceholdersOfTheDraft() {
        Prepared prepared = prepared();

        PreviewView view = inTenant(prepared.tenant(), () -> previews.draft(prepared.survey().id(), "en"));

        assertThat(view.locale()).isEqualTo("en");
        assertThat(view.pages()).singleElement().satisfies(page -> {
            assertThat(page.id()).isEqualTo(Documents.PAGE_ID);
            assertThat(page.title()).isEqualTo("Page");
            assertThat(page.questions()).hasSize(2);
            assertThat(page.questions().get(0).label()).isEqualTo("Question");
            assertThat(page.questions().get(1).options()).hasSize(2);
        });
    }

    @Test
    void anotherTenantsSurveyAnswersAsIfItDidNotExist() {
        Prepared prepared = prepared();
        TenantId other = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> inTenant(other, () -> simulations.draft(prepared.survey().id(), Map.of())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> inTenant(other, () -> previews.draft(prepared.survey().id(), "en")))
                .isInstanceOf(NotFoundException.class);
    }

    /** One expression over the node budget, which the schema accepts and only the parser rejects. */
    private static Map<String, Object> overBudget() {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", "broken");
        variable.put("expr", deepNot(17));
        return variable;
    }

    private static Object deepNot(int depth) {
        Object expression = Map.of("answer", Documents.TEXT_QUESTION_ID);
        for (int level = 0; level < depth; level++) {
            expression = Map.of("op", "not", "args", List.of(expression));
        }
        return expression;
    }

    private Prepared prepared() {
        Prepared prepared = unprepared();
        Map<String, Object> document = Documents.valid();
        document.put("computed", new ArrayList<>(List.of(answered())));
        inTenant(prepared.tenant(), () -> saveDraft.execute(
                prepared.survey().id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        return prepared;
    }

    private static Map<String, Object> answered() {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", "answered");
        variable.put("expr", Map.of("op", "exists", "args", List.of(Map.of("answer", Documents.TEXT_QUESTION_ID))));
        return variable;
    }

    private Prepared unprepared() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        return new Prepared(tenant, survey);
    }

    private record Prepared(TenantId tenant, Survey survey) {}
}
