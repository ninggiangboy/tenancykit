package dev.ngb.survey.authoring.domain.version;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.support.Documents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A question whose type changed cannot keep its id — an aggregate would mix a choice's counts with a number's — so the
 * publish gives it one and moves every reference with it.
 */
class QuestionRetypingTest {

    private static final UUID NEW_ID = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");
    private static final IdGenerator FIXED = () -> NEW_ID;

    @Test
    void aRetypedQuestionGetsANewIdAndItsReferencesMoveWithIt() {
        Map<String, Object> previous = Documents.valid();
        Map<String, Object> draft = retypedDraft();

        QuestionRetyping.Result result = QuestionRetyping.apply(previous, draft, FIXED);

        UUID oldId = UUID.fromString(Documents.CHOICE_QUESTION_ID);
        assertThat(result.changes()).containsExactly(new QuestionRetyping.Change(NEW_ID, oldId));
        assertThat(Documents.question(result.document(), 0, 1).get("id"))
                .isEqualTo(NEW_ID.toString());
        assertThat(Documents.question(result.document(), 0, 0).get("visible_if"))
                .as("a condition follows the question it names")
                .isEqualTo(Map.of("answer", NEW_ID.toString()));
        assertThat(scoringQuestion(result.document()))
                .as("a scoring rule follows the question it names")
                .isEqualTo(NEW_ID.toString());
        assertThat(Documents.translations(result.document(), "en"))
                .containsKey("question." + NEW_ID + ".label")
                .doesNotContainKey("question." + Documents.CHOICE_QUESTION_ID + ".label");
    }

    @Test
    void theDraftTheCallerSentIsNeverMutated() {
        Map<String, Object> draft = retypedDraft();
        Map<String, Object> before = Json.copyOf(draft);

        QuestionRetyping.apply(Documents.valid(), draft, FIXED);

        assertThat(draft).isEqualTo(before);
    }

    @Test
    void anUnchangedTypeKeepsTheDocumentAndRecordsNothing() {
        Map<String, Object> draft = Documents.valid();

        QuestionRetyping.Result result = QuestionRetyping.apply(Documents.valid(), draft, FIXED);

        assertThat(result.changes()).isEmpty();
        assertThat(result.document()).isEqualTo(draft);
        assertThat(Documents.question(result.document(), 0, 1).get("id"))
                .isEqualTo(Documents.CHOICE_QUESTION_ID);
    }

    @Test
    void theFirstPublishRetypesNothing() {
        QuestionRetyping.Result result = QuestionRetyping.apply(null, Documents.valid(), FIXED);

        assertThat(result.changes()).isEmpty();
        assertThat(result.document()).isEqualTo(Documents.valid());
    }

    @SuppressWarnings("unchecked")
    private static String scoringQuestion(Map<String, Object> document) {
        Map<String, Object> scoring = (Map<String, Object>) document.get("scoring");
        List<Object> rules = (List<Object>) scoring.get("rules");
        Map<String, Object> rule = (Map<String, Object>) rules.getFirst();
        return (String) rule.get("question");
    }

    /** The same survey with its choice question made a number, and a condition and a rule naming the old id. */
    private static Map<String, Object> retypedDraft() {
        Map<String, Object> draft = Documents.valid();
        Map<String, Object> question = Documents.question(draft, 0, 1);
        question.put("type", "number");
        Documents.removeOptions(question, draft);

        Documents.question(draft, 0, 0).put("visible_if", Map.of("answer", Documents.CHOICE_QUESTION_ID));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("question", Documents.CHOICE_QUESTION_ID);
        rule.put("when", true);
        rule.put("points", 2);
        List<Object> rules = new ArrayList<>();
        rules.add(rule);
        @SuppressWarnings("unchecked")
        Map<String, Object> scoring = (Map<String, Object>) draft.get("scoring");
        scoring.put("rules", rules);
        return draft;
    }
}
