package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answers a flow runs over, keyed by stable question id.
 *
 * <p>A matrix answer is an object of row id to column id, and a file answer is a list of upload ids; both arrive from
 * the same JSON a respondent's browser sends. Reading never throws: a member of the wrong shape reads as null, so a
 * malformed answer behaves like an absent one instead of failing a submit.
 */
public final class Answers {

    private final Map<String, Object> submitted;

    private Answers(Map<String, Object> submitted) {
        this.submitted = submitted;
    }

    public static Answers of(Map<String, Object> submitted) {
        return new Answers(submitted == null ? Map.of() : submitted);
    }

    public static Answers empty() {
        return new Answers(Map.of());
    }

    /** The value of one answer, or one matrix cell when {@code rowId} is present. */
    public Value value(String questionId, String rowId) {
        Object raw = submitted.get(questionId);
        if (rowId != null) {
            Map<String, Object> cells = Json.object(raw);
            return cells == null ? Value.NIL : Value.of(cells.get(rowId));
        }
        return Value.of(raw);
    }

    /** Every matrix cell of one question, as row id to column id, or an empty map when it is not a matrix answer. */
    public Map<String, Object> cells(String questionId) {
        Map<String, Object> cells = Json.object(submitted.get(questionId));
        return cells == null ? Map.of() : cells;
    }

    /** The answers as the response carries them, for a caller that needs to store them. */
    public Map<String, Object> submitted() {
        return submitted;
    }

    /** Whether the respondent sent any answer at all for this question. */
    public boolean answered(String questionId) {
        return submitted.containsKey(questionId);
    }

    /** The answers with a member replaced, which a simulation uses to walk a flow question by question. */
    public Answers with(String questionId, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(submitted);
        copy.put(questionId, value);
        return new Answers(copy);
    }

    /** Only the answers whose question ids are in this version, so a v1 draft resumed after v2 keeps no stray keys. */
    public Answers only(List<String> questionIds) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String id : questionIds) {
            if (submitted.containsKey(id)) {
                copy.put(id, submitted.get(id));
            }
        }
        return new Answers(copy);
    }
}
