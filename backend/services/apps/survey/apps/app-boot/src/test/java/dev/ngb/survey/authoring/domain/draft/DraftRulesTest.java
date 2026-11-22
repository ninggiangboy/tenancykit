package dev.ngb.survey.authoring.domain.draft;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.IdGenerator;
import org.assertj.core.api.InstanceOfAssertFactories;
import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.support.Documents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules a document obeys beyond the schema: assigned ids, unique ids, forward jumps, translations and identity. */
class DraftRulesTest {

    private static final IdGenerator IDS = () -> UUID.randomUUID();

    @Test
    void theFixtureDocumentHasNoProblem() {
        assertThat(DraftRules.problems(Documents.valid())).isEmpty();
    }

    @Test
    void everyStructuralNodeWithoutAnIdGetsOneAndTheIdsSentAreKept() {
        Map<String, Object> document = new LinkedHashMap<>();
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", Documents.OPTION_A_ID);
        option.put("value", "a");

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "single_choice");
        question.put("options", new ArrayList<>(List.of(option)));

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("questions", new ArrayList<>(List.of(question)));
        document.put("pages", new ArrayList<>(List.of(page)));

        Map<String, Object> assigned = DraftRules.withIds(document, IDS);

        assertThat(Documents.page(assigned, 0).get("id")).isInstanceOf(String.class);
        assertThat(Documents.question(assigned, 0, 0).get("id")).isInstanceOf(String.class);
        assertThat(Documents.options(Documents.question(assigned, 0, 0)))
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id", Documents.OPTION_A_ID);
        assertThat(document.get("pages")).as("the caller's document is never mutated").isEqualTo(List.of(page));
    }

    @Test
    void anIdTwoNodesShareIsReportedAtTheSecondOne() {
        Map<String, Object> document = Documents.valid();
        Documents.question(document, 0, 1).put("id", Documents.TEXT_QUESTION_ID);

        assertHas(DraftRules.problems(document), "/pages/0/questions/1/id", ValidationCode.DUPLICATE_ID);
    }

    @Test
    void aJumpToAPageThisVersionDoesNotHaveIsDangling() {
        Map<String, Object> document = Documents.valid();
        jumpTo(document, Documents.PAGE_ID, "99999999-9999-7999-8999-999999999999");

        assertHas(
                DraftRules.problems(document),
                "/pages/0/jumps/0/to_page",
                ValidationCode.DANGLING_REFERENCE);
    }

    @Test
    void aJumpToTheEndIsAllowed() {
        Map<String, Object> document = Documents.valid();
        jumpTo(document, Documents.PAGE_ID, "end");

        assertThat(DraftRules.problems(document)).isEmpty();
    }

    @Test
    void aJumpBackwardsIsACycle() {
        String secondPage = "66666666-6666-7666-8666-666666666666";
        Map<String, Object> document = Documents.valid();
        Documents.addPage(document, secondPage);
        jumpTo(document, secondPage, Documents.PAGE_ID);

        assertHas(DraftRules.problems(document), "/pages/1/jumps/0/to_page", ValidationCode.CYCLE);
    }

    @Test
    void everyMissingTranslationKeyIsListedByPointer() {
        Map<String, Object> document = Documents.valid();
        Documents.translations(document, "en").remove("question." + Documents.TEXT_QUESTION_ID + ".help");
        Documents.translations(document, "en").remove("question." + Documents.CHOICE_QUESTION_ID + ".help");

        List<ValidationError> errors = DraftRules.problems(document);

        assertThat(errors)
                .extracting(ValidationError::pointer)
                .contains(
                        "/translations/en/question." + Documents.TEXT_QUESTION_ID + ".help",
                        "/translations/en/question." + Documents.CHOICE_QUESTION_ID + ".help");
        assertThat(errors.stream().filter(error -> error.code() == ValidationCode.TRANSLATION_MISSING))
                .hasSize(2);
    }

    @Test
    void aSecondEnabledLocaleNeedsTheSameKeys() {
        Map<String, Object> document = Documents.valid();
        @SuppressWarnings("unchecked")
        List<String> locales = (List<String>) Documents.settings(document).get("locales");
        locales.add("vi");

        List<ValidationError> errors = DraftRules.problems(document);

        assertThat(errors).isNotEmpty();
        assertThat(errors).allMatch(error -> error.pointer().startsWith("/translations/vi/"));
    }

    @Test
    void aTranslationKeyThatNamesNothingIsDangling() {
        Map<String, Object> document = Documents.valid();
        Documents.translations(document, "en").put("page.99999999-9999-7999-8999-999999999999.title", "Gone");

        assertHas(
                DraftRules.problems(document),
                "/translations/en/page.99999999-9999-7999-8999-999999999999.title",
                ValidationCode.DANGLING_REFERENCE);
    }

    @Test
    void onlyOneQuestionMayCarryTheRespondentsIdentity() {
        Map<String, Object> document = Documents.valid();
        Documents.question(document, 0, 1).put("identity", true);

        assertHas(DraftRules.problems(document), "/pages/0/questions/1/identity", ValidationCode.LIMIT_EXCEEDED);
    }

    @Test
    void theDefaultLocaleMustBeEnabled() {
        Map<String, Object> document = Documents.valid();
        Documents.settings(document).put("default_locale", "fr");

        assertHas(DraftRules.problems(document), "/settings/default_locale", ValidationCode.REQUIRED);
    }

    private static void jumpTo(Map<String, Object> document, String pageId, String toPage) {
        Map<String, Object> jump = new LinkedHashMap<>();
        jump.put("when", true);
        jump.put("to_page", toPage);
        List<Object> jumps = new ArrayList<>();
        jumps.add(jump);
        for (Object page : Documents.pages(document)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) page;
            if (pageId.equals(node.get("id"))) {
                node.put("jumps", jumps);
            }
        }
    }

    private static void assertHas(List<ValidationError> errors, String pointer, ValidationCode code) {
        assertThat(errors)
                .as("a " + code + " at " + pointer + " in " + errors)
                .anySatisfy(error -> {
                    assertThat(error.pointer()).isEqualTo(pointer);
                    assertThat(error.code()).isEqualTo(code);
                });
    }
}
