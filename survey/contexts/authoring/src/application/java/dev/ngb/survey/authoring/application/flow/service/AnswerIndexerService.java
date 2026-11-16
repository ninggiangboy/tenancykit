package dev.ngb.survey.authoring.application.flow.service;

import dev.ngb.survey.authoring.api.flow.AnswerIndexer;
import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a submitted answer set into the typed rows an aggregate reads, and answers the two questions a submit asks of
 * a version: which question carries the respondent's identity and which questions accept a file.
 *
 * <p>It lives in authoring because only a version knows a question's type: a choice stores the option's stable value,
 * a matrix cell one row carrying both the row and the column, a number its decimal. {@code collection} stores the
 * rows without interpreting them, which is what keeps a query from parsing the response's JSONB.
 */
public class AnswerIndexerService implements AnswerIndexer {

    @Override
    public List<AnswerRow> index(Map<String, Object> document, Map<String, Object> answers) {
        Map<String, String> types = questionTypes(document);
        List<AnswerRow> rows = new ArrayList<>();
        answers.forEach((questionId, raw) -> {
            UUID id = uuid(questionId);
            String type = types.get(questionId);
            if (id == null || type == null) {
                return;
            }
            rows.addAll(rowsOf(id, type, raw));
        });
        return List.copyOf(rows);
    }

    @Override
    public java.util.Optional<String> identityQuestion(Map<String, Object> document) {
        for (Map<String, Object> question : questions(document)) {
            if (Boolean.TRUE.equals(question.get("identity"))) {
                return java.util.Optional.ofNullable(Json.text(question.get("id")));
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<FileLimit> fileLimit(Map<String, Object> document, String questionId) {
        for (Map<String, Object> question : questions(document)) {
            if (!questionId.equals(Json.text(question.get("id"))) || !"file".equals(Json.text(question.get("type")))) {
                continue;
            }
            Map<String, Object> constraints = Json.object(question.get("constraints"));
            if (constraints == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new FileLimit(
                    strings(constraints.get("allowed_mime")),
                    number(constraints.get("max_bytes"), 0),
                    (int) number(constraints.get("max_files"), 1)));
        }
        return java.util.Optional.empty();
    }

    private static List<String> strings(Object raw) {
        List<Object> members = Json.array(raw);
        if (members == null) {
            return List.of();
        }
        List<String> texts = new ArrayList<>(members.size());
        for (Object member : members) {
            String text = Json.text(member);
            if (text != null) {
                texts.add(text);
            }
        }
        return List.copyOf(texts);
    }

    private static long number(Object raw, long fallback) {
        return raw instanceof Number number ? number.longValue() : fallback;
    }

    private static List<AnswerRow> rowsOf(UUID questionId, String type, Object raw) {
        List<AnswerRow> rows = new ArrayList<>();
        if ("matrix".equals(type)) {
            Map<String, Object> cells = Json.object(raw);
            if (cells != null) {
                cells.forEach((rowId, columnId) -> rows.add(
                        new AnswerRow(questionId, uuid(text(columnId)), uuid(rowId), null, null, null)));
            }
            return rows;
        }
        List<Object> members = Json.array(raw);
        if (members != null) {
            // A multi-choice answer stores one row per chosen value, which is what a count per option reads.
            for (Object member : members) {
                Value value = Value.of(member);
                rows.add(choiceOrValue(questionId, type, value));
            }
            return rows;
        }
        rows.add(choiceOrValue(questionId, type, Value.of(raw)));
        return rows;
    }

    /** A choice stores the option's value as text; a number its decimal; a date its date; anything else its text. */
    private static AnswerRow choiceOrValue(UUID questionId, String type, Value value) {
        return switch (type) {
            case "number", "rating", "nps" -> new AnswerRow(
                    questionId, null, null, value instanceof Value.Num number ? number.value() : null, null, null);
            case "date" -> new AnswerRow(questionId, null, null, null, null, dateOf(value));
            default -> new AnswerRow(questionId, null, null, null, textOf(value), null);
        };
    }

    private static String textOf(Value value) {
        return value instanceof Value.Text text ? text.value() : null;
    }

    private static LocalDate dateOf(Value value) {
        if (!(value instanceof Value.Text text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.value());
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    private static Map<String, String> questionTypes(Map<String, Object> document) {
        Map<String, String> types = new java.util.LinkedHashMap<>();
        for (Map<String, Object> question : questions(document)) {
            String id = Json.text(question.get("id"));
            String type = Json.text(question.get("type"));
            if (id != null && type != null) {
                types.put(id, type);
            }
        }
        return types;
    }

    private static List<Map<String, Object>> questions(Map<String, Object> document) {
        List<Map<String, Object>> questions = new ArrayList<>();
        List<Object> pages = Json.array(document.get("pages"));
        if (pages == null) {
            return questions;
        }
        for (Object rawPage : pages) {
            Map<String, Object> page = Json.object(rawPage);
            List<Object> members = page == null ? null : Json.array(page.get("questions"));
            if (members == null) {
                continue;
            }
            for (Object rawQuestion : members) {
                Map<String, Object> question = Json.object(rawQuestion);
                if (question != null) {
                    questions.add(question);
                }
            }
        }
        return questions;
    }

    private static String text(Object raw) {
        return Json.text(raw);
    }

    private static UUID uuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException notAnId) {
            return null;
        }
    }
}
