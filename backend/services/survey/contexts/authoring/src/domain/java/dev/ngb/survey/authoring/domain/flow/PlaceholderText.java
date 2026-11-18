package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a translated sentence for a builder: plain text as itself, and a piped token as the placeholder that names
 * what it will show once a respondent has answered.
 *
 * <p>A preview must not invent a value, so {@code {"ref": {"answer": "q1"}}} renders as {@code {{answer:q1}}} rather
 * than as an empty string that a builder could mistake for an intentionally blank sentence.
 */
public final class PlaceholderText {

    private PlaceholderText() {}

    /** The stored translation value rendered with its tokens named rather than substituted. */
    public static String render(Object stored) {
        if (stored == null) {
            return null;
        }
        String text = Json.text(stored);
        if (text != null) {
            return clip(text);
        }
        List<Object> tokens = Json.array(stored);
        if (tokens == null) {
            return null;
        }
        StringBuilder rendered = new StringBuilder();
        for (Object token : tokens) {
            String literal = Json.text(token);
            if (literal != null) {
                rendered.append(literal);
            } else {
                rendered.append(placeholder(Json.object(token)));
            }
            if (rendered.length() >= Limits.PIPED_CHARACTERS) {
                break;
            }
        }
        return clip(rendered.toString());
    }

    private static String placeholder(Map<String, Object> token) {
        if (token == null || !token.containsKey("ref")) {
            return "";
        }
        Map<String, Object> reference = Json.object(token.get("ref"));
        if (reference == null) {
            return "";
        }
        String questionId = Json.text(reference.get("answer"));
        if (questionId != null) {
            String row = Json.text(reference.get("row"));
            return row == null
                    ? "{{answer:" + questionId + "}}"
                    : "{{answer:" + questionId + "#" + row + "}}";
        }
        String variableName = Json.text(reference.get("var"));
        return variableName == null ? "" : "{{var:" + variableName + "}}";
    }

    private static String clip(String text) {
        return text.length() <= Limits.PIPED_CHARACTERS ? text : text.substring(0, Limits.PIPED_CHARACTERS);
    }

    /** The rendered entries of one locale, keyed by translation key. */
    public static Map<String, String> renderAll(Map<String, Object> translations) {
        Map<String, String> rendered = new java.util.LinkedHashMap<>();
        translations.forEach((key, stored) -> rendered.put(key, render(stored)));
        return rendered;
    }

    /** Every page id of a document, in order, for a caller that walks the presentation order. */
    public static List<String> pageIds(Map<String, Object> document) {
        List<String> ids = new ArrayList<>();
        List<Object> pages = Json.array(document.get("pages"));
        if (pages == null) {
            return ids;
        }
        for (Object page : pages) {
            Map<String, Object> node = Json.object(page);
            String id = node == null ? null : Json.text(node.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
