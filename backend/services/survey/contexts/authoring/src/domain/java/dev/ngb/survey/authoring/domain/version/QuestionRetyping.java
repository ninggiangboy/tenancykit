package dev.ngb.survey.authoring.domain.version;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.survey.authoring.domain.document.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gives a question whose type changed a new id at publish, and moves every reference with it.
 *
 * <p>Ids are the join between versions, so a question whose type changed cannot keep its id: an aggregate would mix a
 * choice's counts with a number's. The old id stays readable through {@link QuestionLineage}, and the version's own
 * logic — {@code visible_if}, {@code skip_if}, jumps, computed variables, scoring rules and the translation keys —
 * is rewritten to the new id, so the published version does not dangle against itself.
 */
public final class QuestionRetyping {

    /** One question that changed type: its new id and the id it replaces. */
    public record Change(UUID questionId, UUID replaces) {}

    /** The document to publish and the lineage the publish records. */
    public record Result(Map<String, Object> document, List<Change> changes) {}

    private QuestionRetyping() {}

    /**
     * @param previous the latest published version's document, or null when this is the first publish
     * @param draft the validated draft document, which is never mutated
     */
    public static Result apply(Map<String, Object> previous, Map<String, Object> draft, IdGenerator ids) {
        Map<String, String> previousTypeById = previous == null ? Map.of() : questionTypes(previous);
        Map<String, Object> document = Json.copyOf(draft);

        Map<String, UUID> replacements = new LinkedHashMap<>();
        for (Map<String, Object> page : objects(document.get("pages"))) {
            for (Map<String, Object> question : objects(page.get("questions"))) {
                String id = Json.text(question.get("id"));
                String type = Json.text(question.get("type"));
                if (id == null || type == null) {
                    continue;
                }
                String previousType = previousTypeById.get(id);
                if (previousType != null && !previousType.equals(type)) {
                    UUID replacement = ids.newId();
                    replacements.put(id, replacement);
                    question.put("id", replacement.toString());
                }
            }
        }
        if (replacements.isEmpty()) {
            return new Result(document, List.of());
        }

        rewriteReferences(document, replacements);
        renameTranslationKeys(document, replacements);

        List<Change> changes = new ArrayList<>(replacements.size());
        replacements.forEach((oldId, newId) -> changes.add(new Change(newId, UUID.fromString(oldId))));
        return new Result(document, List.copyOf(changes));
    }

    private static Map<String, String> questionTypes(Map<String, Object> document) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> page : objects(document.get("pages"))) {
            for (Map<String, Object> question : objects(page.get("questions"))) {
                String id = Json.text(question.get("id"));
                String type = Json.text(question.get("type"));
                if (id != null && type != null) {
                    types.put(id, type);
                }
            }
        }
        return types;
    }

    /**
     * Walks the whole document: {@code {"answer": "<old id>"}} and a scoring rule's {@code "question": "<old id>"} are
     * the two places a question is named. The translation map is walked too, because a piped token holds an expression.
     */
    private static void rewriteReferences(Object node, Map<String, UUID> replacements) {
        Map<String, Object> object = Json.object(node);
        if (object != null) {
            for (Map.Entry<String, Object> member : object.entrySet()) {
                if ((member.getKey().equals("answer") || member.getKey().equals("question"))
                        && member.getValue() instanceof String id
                        && replacements.containsKey(id)) {
                    member.setValue(replacements.get(id).toString());
                } else {
                    rewriteReferences(member.getValue(), replacements);
                }
            }
            return;
        }
        List<Object> array = Json.array(node);
        if (array != null) {
            for (Object member : array) {
                rewriteReferences(member, replacements);
            }
        }
    }

    /** {@code question.<old>.label} becomes {@code question.<new>.label}, help included. */
    private static void renameTranslationKeys(Map<String, Object> document, Map<String, UUID> replacements) {
        Map<String, Object> translations = Json.object(document.get("translations"));
        if (translations == null) {
            return;
        }
        for (Object locale : translations.values()) {
            Map<String, Object> entries = Json.object(locale);
            if (entries == null) {
                continue;
            }
            Map<String, Object> renamed = new LinkedHashMap<>();
            entries.forEach((key, text) -> renamed.put(rename(key, replacements), text));
            entries.clear();
            entries.putAll(renamed);
        }
    }

    private static String rename(String key, Map<String, UUID> replacements) {
        for (Map.Entry<String, UUID> replacement : replacements.entrySet()) {
            String prefix = "question." + replacement.getKey() + ".";
            if (key.startsWith(prefix)) {
                return "question." + replacement.getValue() + key.substring(prefix.length() - 1);
            }
        }
        return key;
    }

    private static List<Map<String, Object>> objects(Object value) {
        List<Object> array = Json.array(value);
        if (array == null) {
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>(array.size());
        for (Object member : array) {
            Map<String, Object> object = Json.object(member);
            if (object != null) {
                objects.add(object);
            }
        }
        return objects;
    }
}
